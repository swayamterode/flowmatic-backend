# Email Send Visibility & AI Output Schema — Design

**Date:** 2026-07-30
**Status:** Approved (pending spec review)
**Source requirements:** [BACKEND-REQUIREMENTS.md](../../../BACKEND-REQUIREMENTS.md)
**Builds on:** [2026-07-23-generic-workflow-engine-design.md](2026-07-23-generic-workflow-engine-design.md)

## Problem

Two capabilities the canvas cannot deliver, both blocked in this service rather than the UI:

1. **A run does not record what was sent.** `EmailOutputNodeExecutor` builds each message, sends it,
   and returns only `{sent, failed, total}` (line 107). The recipient, resolved subject and body are
   discarded, so the run panel can show "sent: 5" and nothing else.
2. **An AI node cannot describe the inside of an array.** `formatInstructions` renders one flat line
   per field (line 100), so `{"name":"customers","type":"array"}` tells the model exactly
   `- "customers": array`. The model guesses the element shape, and no client can honestly offer
   `{{item.email}}`. Worse, the response is never checked against the declared schema, so a field
   declared `array` that comes back as a string reports **SUCCESS** and fails one node later as
   `forEach did not resolve to a list` — naming neither the node nor the field at fault.

What already works and is **not** rebuilt: `to`/`subject`/`body` are already templates over the
namespaced context (lines 75–81), and `forEach` already puts each element in scope as `item`
(lines 112–128). So a single AI-authored email is expressible today. Binding those fields for the
user is frontend work and out of scope here.

## Goals

- The OUTPUT node records every message it attempted, with per-message status, bounded in size.
- An `array` output field can declare its element fields, and the model is told that shape.
- The AI node fails on itself when the model's response contradicts the declared schema.

## Non-goals

- **A dry-run / preview endpoint.** `execute` resolves and sends in one loop with no mode that stops
  short of `mailSender.send`. Not requested. Change 1 gets most of the way there after the fact.
- Nested `items` inside `items` (one level of element description only).
- Any database migration. `output_json` is already a JSON column; `node_type` and `status` are
  VARCHAR specifically so new values need no schema change.
- Making template errors per-item recoverable. A broken template still aborts the batch; only the
  record of what was already sent is preserved. See "Partial record on abort".

## Key decisions

1. **Bodies are recorded in full**, capped at 2000 characters. Decided by the service owner, who was
   asked explicitly: message bodies become durable rows in `node_run_logs` readable by anything with
   database access, and may contain whatever the model wrote about a customer. The alternative
   (record `to` + `subject`, omit `body`) was declined in favour of the run panel showing the actual
   text.
2. **A failed node can carry an output.** Without this, a template error mid-batch discards the
   record of every email already sent — the exact blindness Change 1 exists to remove. Rather than
   documenting that as a limitation, `NodeExecutionResult` gains a failure-with-output factory and
   `WorkflowExecutionService` persists it. Fail-fast semantics are unchanged.
3. **Element validation is included.** An `array` declared with `items` also checks that each element
   is an object carrying every declared item name. This is broader than the requirements' written
   acceptance criteria, and deliberate: an array of bare strings is exactly the failure Change 3
   exists to catch, and leaving it unchecked sends the user to debug the email node again.
4. **Schema handling is extracted** from `AiNodeExecutor` into `AiOutputSchema`. Rendering and
   validating a schema is a separate concern from resolve-prompt / call-model / parse, and inlining
   it would roughly double a 122-line executor.
5. **Unrecognised type names are not validated.** Types are freeform strings today, so an existing
   workflow declaring `type: "date"` must not start failing.

---

## Change 1 — the OUTPUT node reports what it sent

### Output shape

`EmailOutputNodeExecutor.execute` returns a `LinkedHashMap` rather than today's `Map.of`, which has
no stable iteration order. The three existing keys keep their meaning and shape — clients read them,
and `WorkflowExecutionIntegrationTest` asserts on `sent`.

```json
{
  "sent": 2,
  "total": 3,
  "failed": ["carol.white@example.com"],
  "messages": [
    { "to": "alice@example.com", "subject": "Thanks Alice",
      "body": "Hi Alice, here is your 20% code SAVE20", "status": "SENT" },
    { "to": "carol.white@example.com", "subject": "Thanks Carol",
      "body": "Hi Carol, ...", "status": "FAILED", "error": "550 mailbox unavailable" }
  ]
}
```

- `messages` is a field on the existing object, never a top-level array: `WorkflowRunController.parse`
  (lines 120–129) does `MAPPER.readValue(json, Map.class)` and silently falls back to the raw string,
  so a top-level array would reach the API as an unparsed string.
- Order matches iteration order, so `messages[i]` lines up with `forEach` element `i`.
- `subject` is the **effective** subject — after the `DEFAULT_SUBJECT` fallback, so it is what
  actually went out, not the unresolved config.
- `status` is `SENT` or `FAILED`. `error` is present only on `FAILED`, carrying the `MailException`
  message currently logged at WARN (line 96) and then lost.
- `failed` keeps its current bare-address shape; it is redundant with `messages` but clients read it.

### Bounds

`output_json` has no length cap, so a 10,000-row CSV would otherwise write every body into the run
log. Two constants beside the existing ones, mirroring `WorkflowExecutionService.ERROR_MESSAGE_MAX`:

| Constant | Value | Effect when exceeded |
|---|---|---|
| `MESSAGES_MAX` | 200 | Later messages are not recorded; `"messagesTruncated": true` on the output |
| `BODY_MAX` | 2000 | Recorded body is cut to 2000 chars; `"bodyTruncated": true` on that message |

Both flags are strictly "exceeded", and absent otherwise: exactly 200 iterations records 200 messages
with no `messagesTruncated` key, and a body of exactly 2000 characters is recorded whole with no
`bodyTruncated` key.

Truncation is declared in the payload, never silent. Only the *recorded* body is truncated — the sent
message is untouched. Subject is left uncapped, matching the requirements.

### Partial record on abort

**Transport** failures behave as the requirements demand: the batch completes and every message is
recorded. A **template** error — an unresolvable `{{…}}`, say an element with no `email` — still
fails the node immediately rather than skipping the item, because a broken template is a workflow bug
rather than a per-recipient hiccup, and that fail-fast behaviour is correct today. But the emails
already sent in that batch must still be visible, so the failure carries the same output object the
success path would have built:

```json
{ "sent": 12, "total": 50, "failed": [],
  "messages": [ "…12 SENT entries…",
    { "to": "dave@example.com", "status": "FAILED",
      "error": "Email template error: could not resolve {{item.email}}" } ] }
```

The aborting item gets its own entry, so `messages[i]` still lines up with `forEach` element `i`.
Fields that never resolved are omitted from that entry: `to` appears only if it resolved before the
throw, and `subject`/`body` are absent.

Two small changes outside the email executor make this possible:

- `NodeExecutionResult.failureWithOutput(String errorMessage, Map<String,Object> output)` — a named
  factory rather than a `failure(String, Map)` overload, which at a call site would read too much
  like the existing `failure(String, String rawDetail)`.
- `WorkflowExecutionService`, failure branch (lines 152–159): set `outputJson` when the result
  carries a **non-empty** output, so every existing failure keeps `output_json` NULL rather than
  starting to write `{}`. The failed node's output is deliberately **not** threaded into the run
  context — the run stops there and downstream nodes must not see it.

`WorkflowRunController.nodeLog` already calls `parse(getOutputJson())` unconditionally, so the
partial record reaches the API with no controller change. The facility is general — the other
`forEach` executor (HTTP) could adopt it later — but this change does not touch it.

---

## Change 2 — an array output declares its element fields

An optional `items` on a field of type `array`:

```json
{ "name": "customers", "type": "array",
  "items": [ {"name":"email","type":"string"}, {"name":"subject","type":"string"} ] }
```

renders nested, at four-space indent:

```
Respond with ONLY a JSON object (no markdown fences, no prose) with these fields:
- "customers": array of objects, each with:
    - "email": string
    - "subject": string
```

Every other field renders exactly today's flat line, byte for byte. `items` is honoured only when
`type` is `array` and the list is non-empty; on any other field it is ignored, as are nested `items`
within an item.

**Backward compatible both ways.** An older server reads only `name` and `type` off each entry, so
an `items` key is ignored rather than rejected. The honest consequence: a client can write `items`
today, but until this ships the model is not told the shape and will not reliably produce it.

This makes the AI-authors-the-email workflow expressible end to end:

```json
{ "id": "email-1", "type": "OUTPUT", "data": {
    "forEach": "{{ai-1.customers}}", "to": "{{item.email}}",
    "subject": "{{item.subject}}", "body": "{{item.body}}" } }
```

---

## Change 3 — validate the model's response against the declared schema

After parsing, each declared field is checked. On the first disagreement the **AI** node fails,
naming the field, with the raw response attached as `rawDetail` —
`WorkflowExecutionService.composeError` (lines 251–261) appends it under
`--- raw model response ---`, which is exactly the surface the user needs.

```
Model response does not match the declared output: "customers" was declared array but a string was returned
Model response does not match the declared output: "customers" was declared array but was not returned
Model response does not match the declared output: "customers[0]" was declared an object but a string was returned
Model response does not match the declared output: "customers[0].email" was declared string but was not returned
```

Type checks are loose — reject genuine shape mismatches only:

| Declared | Accepts | Rejects |
|---|---|---|
| `string` | `String`, `Number`, `Boolean` (the CSV reader already yields `"rating": "5"`) | list, map, null |
| `number` / `integer` | `Number`, numeric `String` | non-numeric string, list, map, null |
| `boolean` | `Boolean`, `"true"` / `"false"` (case-insensitive) | other scalars, list, map, null |
| `array` | `List` | map, scalar, null |
| `object` | `Map` | list, scalar, null |
| anything else | everything — unvalidated | nothing |

- A declared field absent from the response fails the same way as a mismatch.
- An explicit JSON `null` counts as a mismatch.
- Undeclared extra fields the model volunteers are kept — the parsed map is returned as-is.
- For an `array` with declared `items`, each element must be a `Map` containing every declared item
  name, each checked by the same loose rules.

---

## Architecture

One new unit, four existing files changed. Validation runs only when an `output` schema is declared —
the no-schema path still returns `{{ai.text}}` untouched.

```
AiNodeExecutor ──uses──> AiOutputSchema  (new, package-private)
                           record Field(String name, String type, List<Field> items)
                           static AiOutputSchema from(Object cfg)
                           boolean isEmpty()
                           String formatInstructions()
                           Optional<String> validate(Map<String,Object> parsed)

EmailOutputNodeExecutor ──> NodeExecutionResult.failureWithOutput(msg, partial)   (new factory)
                                     ▲
WorkflowExecutionService ────────────┘  persists a non-empty output on the failure branch
```

`AiNodeExecutor` keeps its existing flow — resolve prompt, append instructions, call the model, parse
— and gains one validation step between parse and success. `NodeRunLog` and `WorkflowRunController`
are untouched, and no database migration is needed.

## Testing

`EmailOutputNodeExecutorTest`

- A successful batch lists every message with `status: SENT`, and the right `to`/`subject`/`body`.
- A partial failure lists the failed one with `status: FAILED` and an `error` carrying the
  `MailException` message; the batch still completes and `sent` is unchanged.
- A single-email node (no `forEach`) produces exactly one entry.
- 250 recipients → 200 entries plus `messagesTruncated: true`.
- A body over 2000 characters → recorded body cut to 2000 plus `bodyTruncated: true` on that message,
  and the *sent* body still complete.
- A template error on the second of three items → the node fails, and the carried output records the
  first message as `SENT` and the second as `FAILED` with the template error.

`WorkflowExecutionServiceTest` (or the existing integration test, whichever fits the current setup)

- A node failing with a non-empty output persists it to `output_json`; a node failing with an empty
  output leaves `output_json` NULL; neither puts the failed node's output into the run context.

`AiNodeExecutorTest`

- A field with `items` produces the nested instructions (asserted as an exact string).
- A field without `items` is unchanged from today's output, byte for byte.
- A declared `array` returned as a string fails with the field name in the message and the raw
  response attached.
- A missing declared field fails.
- An extra undeclared field passes through.
- An array of bare strings where `items` was declared fails naming `customers[0]`; an element missing
  a declared item name fails naming `customers[0].subject`.
- A number returned for a declared `string` passes.

The prompt is captured off the existing deep-stub `ChatClient` mock. Existing tests in both files
must keep passing unchanged, as must `WorkflowExecutionIntegrationTest`.

## Summary

| Change | Files | Unblocks |
|---|---|---|
| 1. Report sent messages | `EmailOutputNodeExecutor` | The run panel showing who was emailed, with what subject and body |
| 1b. Partial record on abort | `NodeExecutionResult`, `WorkflowExecutionService` | Seeing what was sent even when the batch aborts on a template error |
| 2. `items` on array outputs | `AiNodeExecutor`, `AiOutputSchema` | Truthful `{{item.…}}` suggestions; AI authoring per-recipient email |
| 3. Validate against schema | `AiNodeExecutor`, `AiOutputSchema` | Failures reported on the node that caused them |

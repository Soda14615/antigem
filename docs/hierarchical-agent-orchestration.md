# Hierarchical Agent orchestration (development preview)

This change extends the existing Jarvis runtime instead of adding a second agent runner or embedding a Paseo daemon. It reuses the native AGY, Claude Code, OpenCode, and Codex backends.

## Profiles and management

From a root Jarvis text chat, register leaf roles first with `jarvis_team_create` (name, agent, model, effort, project, full role instructions). Register manager roles afterward with `child_roles`, an array of existing profile names. Missing references and cycles are rejected. For example, register Researcher and Reviewer, then register Research lead with child_roles=["Researcher","Reviewer"]. Start Research lead with `jarvis_task_start(team="Research lead", goal=...)`.

The leader gets its own tracked conversation and a local capability for the delegated orchestration tools. It can list its permitted roles, start its children using their configured model, read its own child results, reply, stop and reassign work. It must end the turn instead of polling. The existing Jarvis event loop delivers child results to the leader; the parent receives the leader's final report after its mission is explicitly marked done and child work is no longer pending. Managers can delegate to further manager profiles using the same graph.

`jarvis_read_chat` supports per-message `char_offset` and returns `next_char_offset` when more text remains. The existing short result notifications are previews, not the sole copy of the report. Active tasks and hierarchy-related task records are retained independent of ordinary finished history.

## Validation and limits

- Pure orchestration rules have regression tests for nested profile graphs, missing references/cycles, task retention, and idle/completion decisions.
- Android build, provider login/real calls, background/resume, approval routing, event replay after process death, and multi-provider concurrency require real-device/CI testing. No simulated agents count as an end-to-end pass.
- Local capabilities scope the new delegated manager calls; the pre-existing shared Jarvis MCP caller resolution is not a security isolation boundary. Agents running in the same Android app/environment are not mutually sandboxed. Do not treat this preview as enforcing adversarial tenant isolation.
- Profile editing currently uses the existing Jarvis MCP tools. A native graphical team editor, configuration import/export, durable event recovery, task budget controls, and explicit application/business approval gates are follow-up work. No project-specific roles or user data are embedded in the source.
- Before integration, verify the open development branch against the latest localization branch and any concurrent local orchestration CLI changes.

## End-to-end acceptance

1. Create two distinct leaf profiles and a manager role; start the manager from a root Jarvis text chat.
2. Confirm three independently running conversations and real provider/model selections. The manager lists only its allowed roles; invalid role and invalid delegated capability requests fail.
3. Confirm that child completion wakes the manager, not the root; the root does not see the manager marked done before child work and mission completion.
4. Read a long child reply across multiple character offsets and compare the joined content with the original transcript.
5. Check a provider failure, fast reply, unanswered approval, follow-up, stop, process restart, and a role graph with 29+ profiles. Verify real IDs and logs.

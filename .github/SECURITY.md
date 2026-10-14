# Security policy

## Supported versions

Only the latest 0.x release gets fixes; there are no maintenance branches before 1.0.

## Reporting a vulnerability

**Please don't open a public issue.** Report it privately through GitHub:
[**Report a vulnerability**](https://github.com/aoreshkov/delva/security/advisories/new) (the *Security* tab →
*Report a vulnerability*).

Include what you can of:

- the Delva version, JDK and operating system;
- the `StoreOptions` and `Durability` the store used;
- a reproducer — a document, a sequence of calls, or a store directory — and what happened against what you expected;
- the output of `JsonStore.verify` if a store is involved.

You'll get an acknowledgement within a week. Once the problem is confirmed, the fix is released as soon as it's
ready, and a GitHub security advisory credits you unless you'd rather not be named.

## What counts

Delva is an embedded library: it trusts the process that opens a store and the files in the store's directory, and it
has no network interface. Reports are most useful where Delva gets something wrong that it promises:

- **Data loss or corruption**: an acknowledged insert missing or altered after a crash under a durability mode that
  promises it, a partial insert visible after a crash, or a read returning bytes other than those stored without an
  error.
- **Untrusted JSON input**: a document or query value that crashes the JVM, hangs, or makes memory grow beyond what
  `StoreOptions` bounds — the [limits](../project/SPEC.md#13-limits) are meant to hold for hostile input.
- **Damaged store files** that make Delva hang, exhaust memory, or return wrong data silently instead of failing with a
  `CorruptionException`.

Out of scope: problems that need an attacker who can already write arbitrary files into the store directory to do
anything beyond the above, and denial of service by legitimately storing very large amounts of data.

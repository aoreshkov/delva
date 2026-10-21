# Roadmap

What Delva does not do yet, and what is meant to come later. Nothing here is dated or promised; the list exists so
the gaps are known rather than discovered.

The on-disk format is versioned, and everything below is designed to be addable without rewriting a store.

## Semantics

- **Normalization forms other than NFC.** A store can be created with NFC normalization
  ([SPEC.md §2.1](SPEC.md)); NFKC, which also folds compatibility characters such as `"ﬁ"` and `"①"`, is a different
  trade and is not offered.
- **A patch that reuses unchanged fragments.** Documents are immutable: `replace` swaps one for a new version in one
  atomic change, but writes the whole new version. A patch that keeps the fragments it doesn't change is a later
  question.

## API

- **A Java facade.** Today's API is Kotlin-first, and value classes make the write path unusable from Java
  ([guide](GUIDE.md#using-it-from-java)). A small facade with plain JVM types would fix it without giving up the value
  classes.

## Engine

- **Retiring deleted documents from the index.** Postings of deleted documents are skipped at read time and dropped
  when segments are compacted; the set of document ids a store tracks only grows. Long-lived stores with heavy delete
  traffic pay for that in memory.
- **Shorter recovery.** Recovery replays the log from the last flushed index watermark. A periodic checkpoint record
  would bound that work on stores with long-running writers.
- **A linear-time regular expression matcher.** `match` and `search` run on `java.util.regex`, a backtracking matcher,
  so evaluation is bounded by a step budget and fails with `QueryLimitException` on patterns that backtrack too much or
  recurse too deep on long strings ([SPEC.md §10.2](SPEC.md)). I-Regexp has no backreferences or lookaround, so an
  automaton matcher (as in RE2) would run in time linear in the string, with no recursion and no budget.

## Measurement

- The benchmark suites live inside the engine module, because they measure internals. Publishing them as a module of
  their own would let them run against the released artifact.
- Published numbers are measured on one Windows machine. They should be reproduced on Linux CI before they are quoted
  as anything but indicative.

# Delva Specification

**Status: draft.** This document can change in incompatible ways until version 1.0. It describes the observable
behavior of the engine (Part 1) and the canonical byte forms that are persisted (Part 2). The on-disk file formats
will be specified in a later part once they're stable.

The key words MUST, MUST NOT and MAY are used as in RFC 2119.

Contents

- Part 1 — Semantics
  1. [Documents](#1-documents)
  2. [Values and equality](#2-values-and-equality)
  3. [Node identity](#3-node-identity)
  4. [Key–value search](#4-keyvalue-search)
  5. [Output](#5-output)
  6. [Types](#6-types)
  7. [Delete](#7-delete)
  8. [Consistency](#8-consistency)
  9. [Compare](#9-compare)
  10. [JSONPath](#10-jsonpath)
- Part 2 — Canonical forms
  11. [Varints](#11-varints)
  12. [Number keys](#12-number-keys)
  13. [Canonical value hash](#13-canonical-value-hash)
  14. [Index terms](#14-index-terms)
  15. [Path hashes](#15-path-hashes)

---

# Part 1 — Semantics

## 1. Documents

### 1.1 Input

A document is one JSON text (RFC 8259) encoded as UTF-8. Any JSON value can be the root: an object, an array, or a
scalar.

- One leading byte-order mark (`EF BB BF`) is skipped. A byte-order mark anywhere else outside a string is an
  unexpected byte; inside a string it's the ordinary character U+FEFF.
- Whitespace is space, tab, line feed and carriage return.
- Documents are immutable once inserted. To change content, replace the document ([7.1](#71-replace)): insert the new
  version and delete the old one in one atomic change.
- Inserting a document that is identical to a stored one is allowed; it gets its own docId.

### 1.2 Rejection

An insert either stores the whole document or nothing. A rejected insert throws `DocumentRejectedException` with
one of these reasons:

| Reason | When |
|---|---|
| `MALFORMED_JSON` | The input isn't valid JSON: a syntax error, an unescaped control character in a string, an invalid escape, or an unexpected byte outside a string (including bytes that aren't valid UTF-8 there). |
| `INVALID_UTF8` | A string or key contains bytes that aren't valid UTF-8 (RFC 3629): overlong forms, encoded surrogates, code points above U+10FFFF, truncated sequences. |
| `INVALID_STRING` | A `\u` escape denotes a surrogate that isn't part of a high-low surrogate pair. |
| `DUPLICATE_KEY` | An object contains the same key twice. Keys are compared by code points after unescaping, so `"a"` and `"\u0061"` are the same key. |
| `NUMBER_OUT_OF_RANGE` | A number's normalized exponent doesn't fit a 32-bit signed integer (see [2.2](#22-numbers)). |
| `LIMIT_EXCEEDED` | The document exceeds a configured limit (see [1.3](#13-limits)). |
| `TYPE_PATH_UNMATCHED` | A type assignment given with the insert selects no value, or selects a value that isn't an object (see [6.1](#61-assigning-types)). |
| `KEY_MISSING` | The store has document keys and the document has no string at the key's path: no value there, or a value that isn't a string (see [3.3](#33-document-keys)). |

The exception carries the byte offset of the problem in the input (counting a leading byte-order mark) when it's
known, and a JSON path when it's known. For `TYPE_PATH_UNMATCHED` that is the path exactly as it was given.

If a document has exactly one problem, that problem is reported. If it has several, which one is reported isn't
specified.

A rejected insert may consume a docId (see [3.1](#31-document-ids)).

### 1.3 Limits

Limits are set when the store is opened. Byte lengths are UTF-8 lengths after unescaping, and after normalization
when the store has it ([2.1](#21-strings)). `T` is the fragment size the store was created with (64 KiB by default).

| Limit | Meaning | Default |
|---|---|---|
| maximum depth | number of open containers at any point: `[[1]]` has depth 2, a scalar root has depth 0 | 1024 |
| maximum document size | bytes of input | unlimited |
| maximum key length | bytes of one key | `max(T/4, 64)`, so 16 KiB |
| maximum string length | bytes of one string value | unlimited |
| maximum number length | characters of one number | `min(4096, max(T/4, 64))`, so 4 KiB |
| maximum document key length | bytes of a document's key ([3.3](#33-document-keys)) | 1024, fixed |
| maximum combining run | with NFC normalization ([2.1](#21-strings)): code points in a row, in the normalized form of a key or string, that are combining marks (general category Mn, Mc or Me) or Hangul vowel and final jamo (U+1160–U+11FF, U+D7B0–U+D7FF) | 30, fixed |

A key and a number are each stored whole within one fragment, so their limits follow from `T` and can't be raised
past it; a string value of any length is stored in chunks and is limited only by the maximum string length. A document
key is held in the key index and in its cursors, so its limit is fixed. The combining run is the bound of the Unicode
Stream-Safe Text Format (UAX #15): only text stacked with marks goes past it, and it keeps normalizing linear.

## 2. Values and equality

Matching (search), comparison and literal-value queries all use the same equality:

| Values | Equal when |
|---|---|
| different JSON types | never: `"1"` ≠ `1`, `0` ≠ `false`, `null` ≠ `""` |
| `null`, `true`, `false` | same literal |
| numbers | same decimal value (see [2.2](#22-numbers)) |
| strings | same sequence of Unicode code points |
| objects | same set of keys, with equal values per key; member order is ignored |
| arrays | same length, with equal elements at each index; order matters |

### 2.1 Strings

Strings compare by code points. By default no Unicode normalization is applied, so two strings that a reader would
call the same word differ when their code points differ: `"é"` as U+00E9 is not equal to `"é"` as U+0065 U+0301, and
neither finds the other in a search.

A store MAY be created with **NFC normalization**, which then applies to every string and key of a document as it is
read, and to every string and key of a query, a type name and a type path. The option is fixed when the store is
created, because it decides the bytes that are stored and the terms that are indexed: a store is always opened with
the setting it was created with, and a request to open it with a different one MUST fail (`CreationOptionsMismatchException`).

With normalization on:

- The normalized form is what the store holds. A document reads back in NFC, not in the spelling it was inserted
  with, the same way it reads back with its escapes resolved.
- Two members of one object whose keys normalize to the same string are duplicate keys and the document is rejected
  with `DUPLICATE_KEY` ([1.2](#12-rejection)).
- Limits ([1.3](#13-limits)) apply to the normalized form, which can be longer than the input: `"क़"` is one code
  point of 3 bytes and normalizes to two code points of 6, and U+0344 is one combining mark and normalizes to two.
  Every spelling of a key or string meets them, or fails them, alike.
- The maximum combining run also applies to the keys and strings of a query, JSONPath names and string literals, type
  names, the names in a type path, and the keys given to `get` or to a search in key order: past it they are rejected
  with `DocumentRejectedException` (`LIMIT_EXCEEDED`), since no stored key or string could match them. A document key
  path with such a name can't be set (`InvalidJsonPathException`).
- Normalization never applies to numbers, which have a canonical form of their own ([2.2](#22-numbers)), or to
  booleans and null.

### 2.2 Numbers

Numbers are exact decimals, never binary floating point: `1`, `1.0`, `10e-1` and `0.1E1` are equal, and `-0` equals
`0`.

Every nonzero number has one normalized form `±0.d₁d₂…dₙ × 10^e` with `d₁ ≠ 0` and `dₙ ≠ 0`. The exponent `e` MUST
fit a 32-bit signed integer; otherwise the document (or query literal) is rejected with `NUMBER_OUT_OF_RANGE`. Zero
is valid with any exponent: `0e3000000000` is `0`.

Examples: `0.1e2147483647` is in range (`e = 2147483647`); `1e2147483647` is not (`e = 2147483648`).

### 2.3 What is stored

A document is stored with its members in insertion order and every number in its original spelling. Whitespace and
the choice of string escapes aren't kept (see [5](#5-output)).

Stored content MAY be compressed, and the same document MAY take a different number of bytes on disk in two stores.
This never changes what is read back: output ([5](#5-output)), equality ([2](#2-values-and-equality)) and the encoded
size of a hit (which counts stored bytes **before** compression) are the same either way.

## 3. Node identity

### 3.1 Document ids

Each document gets a `DocId` when its insert starts. DocIds increase in the order inserts start; an insert that
starts later never gets a smaller docId. The docId of an acknowledged insert is never reused, and gaps are allowed:
an insert that is rejected or fails may leave its docId unused. After a crash, the docId of an insert that was never
acknowledged may be given to a later insert.

The order of docIds is the order in which inserts started, not the order in which they were acknowledged.

### 3.2 Nodes

Every JSON value in a document is a node, numbered in pre-order:

- the root is node 0;
- a container's members or elements follow it, in document order, each followed by its own descendants;
- the `size` of a node is its number of descendants (0 for scalars and empty containers).

Node `B` is inside node `A` exactly when `A.pre < B.pre ≤ A.pre + A.size`. Sorting nodes by `pre` gives document
order.

A `NodeRef` is `(docId, pre)`. Because documents are immutable, a `NodeRef` never changes meaning. It becomes invalid
when its document is deleted.

Every node also has a **Normalized Path** (RFC 9535 §2.7, written as in [9.3](#93-differences)) from its document's
root: `$` for the root, then `['key']` for each member and `[i]` for each element on the way down. `pathOf(nodeRef)`
returns it, reading the fragments from the root down to the node; it fails as `typeOf` does ([6.2](#62-reading-types)).
Search hits carry the path of their node ([4.3](#43-result-modes), [10.4](#104-search)).

Example (pre numbers in brackets):

```
[0]{"order":[1]{"id":[2]7,"items":[3][[4]{"sku":[5]"A1","qty":[6]2},[7]{"sku":[8]"B2","qty":[9]1.0}],"tags":[10][[11]"x",[12]"y"]}}
```

Node 8 is `$['order']['items'][1]['sku']`.

### 3.3 Document keys

A store MAY be created with a **document key**: the path of a string member that names each document, such as
`$.id`, written as type paths are ([6.1](#61-assigning-types)) and not `$` itself. It is fixed when the store is
created, like normalization ([2.1](#21-strings)). In such a store:

- Every document MUST have a string at the path, of at most 1024 bytes ([1.3](#13-limits)), or it's rejected with
  `KEY_MISSING` or `LIMIT_EXCEEDED`. Keys compare by code points: `"a"` and `"A"` are different keys. With NFC
  normalization a key is normalized like every string, and so are the keys given to `get` and the prefixes of a search
  in key order.
- **No two visible documents have the same key.** An insert whose key a visible document has fails with
  `WriteConflictException`, which names that document and the key, and nothing changes. The check is made when the
  insert takes effect, so of two inserts of one new key, concurrent or in one `insertAll`, exactly one succeeds.
- `upsert(document)` inserts the document and deletes the visible document with the same key, if there is one, as
  one atomic change, as `replace` does ([7.1](#71-replace)). Of two upserts of one key, the one that takes effect last
  wins. An input of `insertAll` MAY ask for this instead of a conflict.
- A replace may give its document another key than the documents it replaces have: the key must be free, or be the
  key of one of them.
- `get(key)` returns the visible document with the key, or none.
- A **search in key order** returns, for a prefix (empty for every key), each visible document whose key starts with
  it, once, with the root as the hit, ordered by key — by code points, which is the order of the keys' UTF-8 bytes —
  ascending or descending. Pages work as in [4.6](#46-pagination), with the last hit's key as the position: resuming
  returns the documents strictly past it in the store's newer state, so a document inserted since with a key the
  earlier pages passed isn't returned, and the cursor reports `mayMissLateCommits` when an insert was in flight when
  its page was produced. The cursor holds the key, so its text is up to about 1,400 characters long. Only a search in
  key order of the same direction takes such a cursor.
- Every search hit of a store with document keys carries the key of its document.

## 4. Key–value search

### 4.1 Query

A query is `key = value` with:

| Field | Meaning | Default |
|---|---|---|
| `key` | a member key; matched exactly (no paths, no wildcards) | — |
| `value` | a JSON value: string, number, boolean, null, or an object or array literal | — |
| `mode` | `WHOLE_DOCUMENT` or `CONTAINING_ELEMENT` ([4.3](#43-result-modes)) | `WHOLE_DOCUMENT` |
| `nested` | `ALL` or `OUTERMOST_ONLY` ([4.5](#45-nested-hits)) | `ALL` |
| `limit` | maximum hits per page | 100 |
| `maxBytes` | a page also ends before a hit that would take the sum of its hits' encoded sizes above this; a page holds at least one hit | no budget |
| `after` | cursor to resume from ([4.6](#46-pagination)) | none |
| `type` | only containing objects of this type match ([6](#6-types)) | none |
| `order` | `ASCENDING` or `DESCENDING` ([4.4](#44-order)) | `ASCENDING` |

The query value follows the document rules: a number outside the range of [2.2](#22-numbers) is rejected with
`NUMBER_OUT_OF_RANGE`, and a string with unpaired surrogates with `INVALID_STRING`.

**What a store indexes.** A store can be created to index only some members, which makes its index smaller:

- only the keys in `indexedKeys` (when it isn't empty), never the keys in `excludedKeys`;
- of **string** values, only those whose UTF-8 length is within `minIndexedStringBytes … maxIndexedStringBytes`.
  Numbers, booleans, null, and the object and array values of `hashTerms` are always indexed.

Documents are stored and read back in full either way: the controls change only what can be searched for. A search
that the controls can't answer — its key isn't indexed, or its string value's length isn't — is rejected with
`QueryRejectedException` (reason `NOT_INDEXED`) instead of returning fewer hits, as an object or array value is in a
store without `hashTerms`. The controls are fixed when a store is created ([8](#8-consistency)).

### 4.2 Matching

An object `O` **contains a match** for `key = value` when either:

1. `O` has a member `key` whose value equals `value`; or
2. `O` has a member `key` whose value is an array, and an element of that array equals `value`.

Rule 2 looks one level into the array only: in `{"k": [[1]]}` the query `k = 1` doesn't match, but `k = [1]` does.

- **Any depth.** Every object in the document is checked, however deeply nested, including objects that are
  elements of arrays: in `[{"k": 1}]` the inner object contains a match for `k = 1`.
- **Scalar roots** never match.
- **Object and array literals** match by the equality of [2](#2-values-and-equality). A store MAY be created without
  support for literal container values; then such queries fail with `QueryRejectedException` (`NOT_INDEXED`).
- **One hit per containing object.** If an object matches several times (`{"tags": ["a", "b", "a"]}` for `tags = "a"`),
  it's one hit.

In the example of [3.2](#32-nodes): `id = 7` matches node 1; `qty = 1` matches node 7 (`1.0` equals `1`);
`tags = "x"` matches node 1 through the array; `items = {"sku": "A1", "qty": 2}` matches node 1; `order.id = 7`
matches nothing.

### 4.3 Result modes

| Mode | Hits |
|---|---|
| `WHOLE_DOCUMENT` | each document with at least one containing object that matches, once; the hit's node is the root |
| `CONTAINING_ELEMENT` | each matching containing object; the hit's node is that object |

A hit's `path` is the Normalized Path of its node ([3.2](#32-nodes)): `$` in `WHOLE_DOCUMENT` mode. It's computed when
first read, from the result's snapshot.

### 4.4 Order

Hits are ordered by `(docId, pre)`: ascending with `order = ASCENDING`, descending with `DESCENDING`. DocIds follow
the order in which inserts started ([3.1](#31-document-ids)), so ascending returns the oldest document first, and
descending the newest first and, within a document, the last node first. Both orders return the same hits; only the
sequence differs. `DESCENDING` with `limit = 1` returns the newest hit.

### 4.5 Nested hits

With `OUTERMOST_ONLY`, a hit is dropped when its node is inside the node of another hit of the same document. This
filter applies to the complete sequence of hits of a document, before ordering and pagination, so the result doesn't
depend on the order or on page boundaries. It has no effect in `WHOLE_DOCUMENT` mode.

With the type filter, only objects of the requested type are hits, and nesting is judged among those hits.

### 4.6 Pagination

A page holds at most `limit` hits, and ends early when the next hit would exceed `maxBytes`. When more hits follow,
the page has a cursor; resuming with it returns the hits strictly past `(docId, pre)` of the last hit in the query's
order: after it ascending, before it descending. A search result returns its pages one after another; a cursor resumes
in a new search. A cursor records its order, and a query of the other order refuses it with `InvalidCursorException`, an
`IllegalArgumentException`, as a search refuses the cursor of a page of objects of a type ([6.2](#62-reading-types)).
Text that isn't a cursor fails to parse with the same exception.

- **Within one search result** pagination is exact: every hit appears exactly once.
- **Resuming in a new search** sees a newer state. Deletes and type changes made in between are reflected. A document
  whose insert started before the earlier page was produced but was acknowledged only later can have a docId in the
  stretch the earlier pages covered — below the cursor ascending, above it descending — and is then not returned. A
  cursor reports this possibility (`mayMissLateCommits`); when it's false, resuming misses nothing, unless a secondary
  returned the cursor ([8.1](#81-secondaries)).
- **A descending search resumed** in a new search returns nothing above the cursor, so documents inserted since the
  first page aren't returned; a new search without a cursor starts again from the newest.

### 4.7 Count

`count(query, atMost)` returns how many hits a search for the query returns from the current state, key–value,
JSONPath ([10.4](#104-search)) or in key order ([3.3](#33-document-keys)), but no more than `atMost`: `min(hits, atMost)`. It counts what the search's pages hold
together — the query's cursor, type, mode and nested-hit filter apply, `limit` and `maxBytes` don't — without reading the
hits' JSON, and it stops at the `atMost`-th hit. A query the search refuses, it refuses the same way. `atMost` MUST NOT
be negative.

## 5. Output

Hits and nodes are returned as JSON text in this exact form:

- no whitespace;
- object members in insertion order;
- numbers in their original spelling;
- strings with these escapes only: `\"`, `\\`, `\b`, `\f`, `\n`, `\r`, `\t`, and `\u00XX` with lowercase hex for other
  characters below U+0020; every other character, including `/`, DEL and non-ASCII characters, as UTF-8;
- no byte-order mark.

An assigned type is metadata and never appears in the output.

A hit also reports its encoded size: the stored bytes of its node, including the parts stored separately, before
compression. It predicts the length of the JSON text roughly, not exactly.

This form is `JsonFormat.COMPACT`, the default. It's the only canonical form; the formatted output of
[5.1](#51-formatted-output) is a presentation of it and is never stored.

### 5.1 Formatted output

A `JsonFormat` other than `COMPACT` writes the tokens of the compact form, in the same order and spelling, with
whitespace (spaces, tabs and `\n`) between them and nowhere else, so dropping the whitespace outside strings gives the
compact text byte for byte — unless a level limit cuts containers out. It has five settings:

- *indent*: one indentation step, empty or 1–16 spaces, or one tab;
- *width* W: 0–10 000 columns;
- *element limit* E and *depth limit* D: 0 or more, unlimited by default;
- *level limit* N: 0 or more, unlimited by default.

**Cut-out containers.** A non-empty container nested more than N levels below the node (a direct element of the node
is 1 level below) is written as `{...}` or `[...]`, and is a scalar for the rules below; N = 0 writes the node's own
elements only: `{"name": "ada", "tags": [...], "address": {...}}`. Empty containers are still `{}` and `[]`. Text with
containers cut out isn't JSON.

A column is a code point of the output text, so `\n` inside a string is two columns and `\u0001` six. An indentation
of level L takes L steps, and a tab step counts as 8 columns.

**Scalars and empty containers** are written as in the compact form; `{}` and `[]` have nothing inside.

**A non-empty container** is written either *on one line* or *broken*. On one line, its elements are separated by
`", "`, and a member is its key, `": "` and its value: `{"id": 7, "tags": ["a", "b"]}`. Broken, the opening bracket is
followed, for each element, by `\n`, the indentation of the container's level plus one, and the element — a member
as its key, `": "` and its value — with `,` after every element but the last, then `\n`, the container's own
indentation and the closing bracket. The root is at level 0.

A container goes on one line when all of these hold, and is broken otherwise:

1. it has at most E elements, and so does every container inside it;
2. it holds no non-empty container nested more than D levels below it (a direct child is 1 level below): D = 0 puts
   only containers of scalars and empty containers on one line;
3. the line fits: the columns before the container on its line — none for the root; otherwise its parent's
   indentation plus one step, and `"key": ` for a member — plus the container on one line, plus 1 when a `,` follows
   it, are at most W.

A container inside a one-line container is on that line too, so the rules are applied from the root down, only to
the root and to the elements of broken containers. Scalars are never broken, so a long string or key can make a line
longer than W. W = 0 breaks every non-empty container. The text doesn't start or end with whitespace.

```
{
  "name": "ada",
  "tags": ["math", "engines"],
  "scores": [
    {"round": 1, "points": 40},
    {"round": 2, "points": 44}
  ]
}
```

(indent two spaces, W = 32.) Reading a node formatted takes memory independent of its size: at most one line's worth
of its tokens is held while the writer decides whether a container fits.

## 6. Types

A type is a name attached to one object node. It's metadata: it doesn't change the stored JSON, and a member named
`@type` in the JSON is ordinary data with no connection to assigned types.

- Only objects can have a type, and an object has at most one.
- A type name is 1–256 bytes of UTF-8 without control characters (Unicode category Cc). Names are compared exactly.
  An invalid name fails with `InvalidTypeNameException`. In a store with normalization ([2.1](#21-strings)), a name
  and the key steps of a path are normalized before they are used, so the name a store holds is the normalized one.

### 6.1 Assigning types

**At insert,** types are given as a map from JSON path to type name, applied atomically with the insert. A path is an
RFC 9535 JSONPath *singular query*: the `abs-singular-query` rule of RFC 9535 §2.3.5.1, with one exception, that an
index can't be negative. Such a path selects at most one value, and selects the same value that RFC 9535 gives it:

```
path          := '$' *(S (name-segment / index-segment))
name-segment  := '[' string-literal ']' / '.' member-name-shorthand
index-segment := '[' ('0' / DIGIT1 *DIGIT) ']'        ; at most 2^53 - 1
S             := *(' ' / '\t' / '\n' / '\r')
```

`string-literal` is a single- or double-quoted string with the escapes of RFC 9535 §2.3.1.1, and a
`member-name-shorthand` starts with a letter, `_` or a character above U+007F, and goes on with those and digits.
So `$.order.items[0]`, `$['order']["items"][0]` and `$.café` are paths. A key with any other character needs quotes:
`$['@type']`, `$['$ref']`, `$['first-name']`. Wildcards, slices, filters, descendant segments (`..`) and several
selectors in one segment select any number of values, so they are not paths. Keys are compared exactly, after
normalization in a store that normalizes ([2.1](#21-strings)); RFC 9535 itself doesn't normalize.

A path that selects nothing, or a value that isn't an object, rejects the insert with `TYPE_PATH_UNMATCHED`. Invalid
path syntax fails with `InvalidJsonPathException`, and two paths that assign different types to the same object with
`IllegalArgumentException`, before the document is read.

The types given with an insert become visible together with the document, and a document is never visible without
them.

**Later,** `assignType(nodeRef, type)` sets the type of an object; `null` removes it. The last assignment wins.
Assigning the type an object already has changes nothing. Assigning to a node that isn't an object, or to a `pre`
beyond the document's last node, fails with `IllegalArgumentException`; to a document that doesn't exist, isn't
committed, or was deleted, with `DocumentNotFoundException`.

`assignTypes(list)` applies several assignments in order as one atomic change: a later assignment to the same object
sees the earlier ones as its current type, and if any assignment fails the checks above, none takes effect. An
acknowledged assignment is durable as an acknowledged insert is ([8](#8-consistency)); after a crash, an assignment
call that wasn't acknowledged has taken effect completely or not at all.

`clearType(type)` removes a type from every object that has it when the change is applied, as one atomic change
with the same durability, and returns how many objects lost it. Objects of deleted documents aren't counted. The name
stays in `listTypes`.

### 6.2 Reading types

- `typeOf(nodeRef)` returns the object's type or `null`; it's `null` for nodes that aren't objects. A missing
  document or node fails as for `assignType`.
- Hits carry the type of their node as the search's snapshot sees it.
- `objectsOfType(type, after, limit)` returns up to `limit` objects of a type in `(docId, pre)` order, strictly after
  `after` when given; resuming with the last one returned continues the list.
- `objectsOfTypePage(type, after, limit)` returns the same objects as a page with a cursor, which is null when no
  objects were left, so a full last page is known to be the last. It resumes only from the cursor of such a page, and
  sees the state of its own call, as a search resumed from a cursor does ([4.6](#46-pagination)).
- `objectsOfTypeFlow(type, after)` streams every object of a type in the same order, strictly after `after` when
  given. One collection reads one snapshot from start to end, as a search does.
- `countOfType(type)` returns the exact number of objects of a type.
- `listTypes()` returns every type name ever assigned (including names no object has any more), sorted by name, with an
  approximate count of objects: exact while no object's type was changed or removed.
- The search type filter ([4.1](#41-query)) restricts containing objects to one type: in `CONTAINING_ELEMENT` mode the
  hits are the matching objects of that type; in `WHOLE_DOCUMENT` mode a document is a hit when one of its objects of
  that type contains a match.
- A type name that no object ever had gives empty results. Every operation that takes a type name fails with
  `InvalidTypeNameException` for an invalid one.

## 7. Delete

`delete(docId)` removes a whole document: its content, its index entries and its type assignments. Parts of a
document can't be deleted.

- `delete` returns `true` when it deleted the document. Deleting an already deleted document does nothing and returns
  `false`.
- Deleting a docId that was never stored fails with `DocumentNotFoundException`. That includes a docId not assigned yet,
  one whose insert hasn't been acknowledged, and one given to an insert that was rejected.
- `deleteAll(docIds)` deletes several documents as one atomic change and returns how many it deleted, skipping
  documents already deleted and repeated docIds. If any docId fails the check above, nothing is deleted.
- After the delete is acknowledged, operations on the document (`typeOf`, `assignType`, reading or comparing a
  `NodeRef`) fail with `DocumentNotFoundException`. New searches and type reads don't return it. `listTypes` counts
  are approximate and may still include its objects.
- A search started before the delete keeps returning the document, readable in full, until the search result is
  closed.
- The disk space of deleted documents is given back over time, in the background: files in which most of the stored
  content belongs to deleted documents are rewritten without it. Files that a search result started before the delete
  may still read are removed only after that result is closed.
- Concurrent assignments and deletes take effect one after another: an assignment fails if the delete of its document
  took effect first, and otherwise succeeds and is removed with the document.
- DocIds of deleted documents are never reused.

### 7.1 Replace

`replace(docIds, document)` inserts `document` and deletes the documents `docIds` as one atomic change: once it is
acknowledged the new document is visible and the others are deleted, and no search, type read or comparison sees one
without the other.

- It is a compare-and-set. Every document of `docIds` MUST still be visible when the change takes effect; when one
  isn't — deleted, replaced by another call first, never stored, or not assigned yet — the call fails with
  `WriteConflictException`, which names those documents in ascending order, and nothing changes. A conflict known
  when the call starts fails it before the document is read, and the call then uses no docId.
- Of two replaces of one document, whether concurrent or in one `insertAll`, at most one succeeds.
- Repeated docIds count once. With no docIds, `replace` is an insert.
- The new document gets a new docId, as any insert does; the replaced documents are deleted as `delete` deletes them,
  with their type assignments, and a search started before the replace keeps returning them.
- The document is inserted as `insert` inserts it, with its types, and a rejected document replaces nothing.
- In `insertAll`, each input MAY name the documents it replaces. An input that conflicts gets a conflict as its
  outcome and the others go on; an input can't replace a document that an earlier input of the same call replaces or
  inserts. In a store with document keys ([3.3](#33-document-keys)) the inputs take effect in order, so an input sees
  the keys of the inputs before it.

## 8. Consistency

- A search sees every insert, delete and type assignment acknowledged before the search started, and nothing
  acknowledged later. The search result keeps that view until it's closed.
- After a crash, an insert is either completely visible (with the types given with it) or completely absent, and a call
  that assigns types or deletes documents has taken effect completely or not at all. A replace's document is visible
  exactly when the documents it replaces are deleted. A deleted document never becomes visible again.
- Reading types (`typeOf`, `objectsOfType`, `countOfType`, `listTypes`) and comparing values ([9](#9-compare)) see the
  operations acknowledged before the call started, like a search.
- Whether acknowledged operations survive a crash depends on the durability mode chosen when the store is opened:

| Mode | Acknowledged when | After a crash |
|---|---|---|
| `SyncEachBatch` (default) | its data is synced to disk | every acknowledged operation is present |
| `Periodic(interval)` | its data is written; syncs follow at the interval | operations acknowledged since the last sync may be missing |
| `None` | its data is written; not synced, except that writing index data first syncs the log and a large document syncs its data before it commits | any operation may be missing |

- Inserts MAY run concurrently. Small documents are logged together in shared group commits; a large document streams
  to a log lane of its own, so neither its size nor a slow input stream delays other inserts. A store writes a bounded
  number of large documents at once; further large inserts wait for a free lane.
- The memory a store uses is bounded by its configuration, never by the size of the documents. When index data
  accumulates faster than it can be written out, inserts wait until it has been; searches never wait for it.
- `insertAll` returns one outcome per input, in order: the document's id, its rejection, or its conflict — a replace
  ([7.1](#71-replace)) or a document key ([3.3](#33-document-keys)) that can't take effect. A rejected document or a conflict doesn't stop the others; any other error ends the call, and the documents whose outcome wasn't returned MAY or MAY
  NOT be visible. Each input carries its own types, as `insert` takes them; they are checked for every input before
  any is read, and an invalid path or name fails the call with nothing inserted.
- A cancelled suspending write reports what happened. Cancelled before it starts, it throws `CancellationException`
  and changes nothing. Once started, it runs to the end and returns its result even if the call was cancelled
  meanwhile, and the caller's next suspension point throws; so a call that throws `CancellationException` never made
  a change. Only reading an input stream stops early: a cancelled insert, replace or upsert stops at its next read of
  the input, and nothing of that document becomes visible, while `insertAll` ends there as on any other error.
- A store directory is used by one open writer at a time (secondaries, [8.1](#81-secondaries), don't count); opening it
  again fails with `StoreLockedException`, which
  names the process that has it open — its pid, start time and host — when that process recorded itself. The record
  is advisory: it is missing when it couldn't be written, and a record left by a process on the same host that is gone
  isn't reported.
- If a write or sync fails, the store stops: that operation and every later one fail with `EngineFailedException`
  until the store is closed and opened again, which recovers every operation that became durable. A write or sync
  that fails while the store closes makes `close` throw `EngineFailedException`. A failed sync is never retried,
  because the data it covered can't be trusted afterwards.
- Opening a store whose stored data is damaged in a way that could hide acknowledged operations fails with
  `CorruptionException`. Damage confined to one document's stored content doesn't stop the store from opening;
  reading the damaged part fails with `CorruptionException`, and searches may miss matches inside it.
- Damage to index files fails the opening when it affects what is read at opening, and otherwise fails the searches
  that read the damaged part; it never makes a search skip index data silently.
- `verify` reads the whole store and reports the damage it finds, without changing anything. It reads one view of the
  store, like a search, so operations running alongside it neither disturb it nor appear in its report, and a store
  whose report lists damage keeps serving everything that isn't damaged. A report that lists nothing means every
  record, block and index entry the store can reach checked out at the moment it was read.
- `fragmentBytes`, `hashTerms`, normalization, the document key ([3.3](#33-document-keys)) and the index controls of
  [4.1](#41-query) are fixed when a store is created and recorded in it. Opening the store uses them, and a request to open it with other values fails. Opening
  a directory that holds no store fails without changing it, unless creating a store there was asked for.
- A store records the **format version** its files are written in. Opening a store in a format version this version
  of Delva doesn't read, as its writer or as a secondary, or resetting it, fails with `UnsupportedFormatException`,
  which names the version found and the versions read, and changes nothing. Another format version is never reported
  as damage, nor damage as another format version. `JsonStore.probe` reads a store's format version without opening
  it: it takes no lock and changes nothing, so it also works beside the writer.
- A store that isn't open can be **reset**: it keeps the options it was created with and loses every document,
  deletion and type, leaving it as it was just after it was created — docIds start again at 1, so a docId, node or
  cursor from before the reset may name a new document afterwards. A crash during a reset leaves the store either as
  it was or empty. A reset reads only the store's metadata, so it also empties a store too damaged to open; it
  fails, changing nothing, when the store is open or the directory holds no store.

### 8.1 Secondaries

- A store MAY be read by **secondaries** beside its writer: stores opened without the lock, in another process or in
  the same one, that offer every read and change nothing in the store's directory. A secondary works with no writer at
  all, and beside a writer on the same host, with the store on a local file system; network file systems aren't
  supported.
- A secondary reads one **view** of the store at a time: what its last refresh, or its opening, read. A view contains
  every operation the writer acknowledged before the refresh that read it started. It MAY also contain operations
  written but not acknowledged yet, which only a crash of the writer's host or a failed sync on the writer can take
  back. The views of one secondary never go back, except across a reset.
- Every read uses the newest view. A search result, a select result, a flow and a comparison keep the view they
  started on until they are closed, however often the secondary refreshes meanwhile. A secondary never refreshes by
  itself.
- DocIds, nodes and cursors mean the same in the writer and its secondaries: a cursor from one resumes on the other,
  unless the store was reset in between. A secondary learns of an insert once the writer logged it, so the cursors it
  returns may miss a small insert the writer had in flight without saying so ([4.6](#46-pagination)).
- Opening a secondary in a directory without a store fails with `StoreNotFoundException`. Damage in data the writer
  had synced fails the opening, or the refresh, with `CorruptionException`; a refresh that fails leaves the view as it
  was. A secondary's `verify` checks its view: the files it lists and the part of the log it read.
- A reset while secondaries are open doesn't wait for them: each keeps its view until its next refresh, which shows
  the empty store.
- A file the writer deletes stays readable to the secondaries that have it open. Its space comes back once no view
  needs it: when every secondary refreshed past it and closed the results that read it, or closed.

## 9. Compare

### 9.1 Operands

Two values can be compared. Each operand is either:

- a **stored node** `(docId, pre)`: the value of that node. A document that doesn't exist, isn't committed or was
  deleted fails with `DocumentNotFoundException`; a `pre` beyond the document's last node with
  `IllegalArgumentException` (as for `typeOf`, [6.2](#62-reading-types)); or
- **JSON text**: any JSON value, read with the rules and limits of an insert ([1](#1-documents)) and rejected the same
  way with `DocumentRejectedException`. It isn't stored and has no types.

Stored operands are checked first, left then right; JSON operands are read afterwards. A comparison sees the
operations acknowledged before it started, like a search ([8](#8-consistency)), and uses memory independent of the
sizes of the values.

### 9.2 Equality

`isEqual(a, b)` is true when the values are equal by [2](#2-values-and-equality), with these options:

| Option | Meaning | Default |
|---|---|---|
| `includeTypes` | objects are equal only when they also have the same assigned type ([6](#6-types)); objects of JSON operands have none | false |
| `ignoreKeys` | object members with these keys are left out of both values, at any depth | none |
| `ignorePaths` | the values at these paths are left out of both values with everything inside them; a path uses the grammar of [6.1](#61-assigning-types) with `$` naming the compared value | none |

A member left out counts as absent in both objects. An array element at an ignored path is neither compared nor
counted, even when only one of the arrays has it; the other elements keep their indexes. A path that selects nothing
has no effect; ignoring `$` makes any two values equal.

A stored node is always equal to itself.

### 9.3 Differences

`diff(a, b)` lists the differences between the left value `a` and the right value `b`, with the options of
[9.2](#92-equality). Two values at a path `p` that isn't ignored give:

| Values | Differences |
|---|---|
| different JSON types | `VALUE_TYPE_CHANGED` at `p`, and nothing inside them |
| equal scalars of one type | none |
| unequal scalars of one type | `CHANGED` at `p` |
| two objects | with `includeTypes` and different assigned types, `ASSIGNED_TYPE_CHANGED` at `p`; then, for each key of either object in unsigned byte order of its UTF-8, `REMOVED` at `p['key']` when only the left object has it, `ADDED` when only the right one has it, and the differences of the two member values otherwise |
| two arrays | for each index `i` from 0 to the longer length minus one: the differences of the two elements, or `REMOVED` at `p[i]` when only the left array has it, `ADDED` when only the right one has it |

Differences are listed in that order, depth first. A diff stops after `maxDifferences` (default 1000).

A path is written as an RFC 9535 *Normalized Path* (§2.7): `$`, then `['key']` for each key and `[i]` for each index,
for example `$['order']['items'][0]`. In a key, `'` and `\` are escaped with a backslash; `\b`, `\f`, `\n`, `\r` and
`\t` stand for those characters, and any other character below U+0020 is written as `\u00xx` in lowercase hex. Every
other character is written as it is. A location has exactly one Normalized Path, and every Normalized Path is a path
of [6.1](#61-assigning-types).

Each difference carries the values involved: `left` except for `ADDED`, `right` except for `REMOVED`. A value has:

- its node, for a stored operand;
- its JSON text in the form of [5](#5-output), cut to its first `maxValueBytes` bytes (default 1024) on a character
  boundary, and whether it was cut;
- its assigned type, for an object with `includeTypes`.

Applying the differences to the left value — setting the right value at each `CHANGED` and `VALUE_TYPE_CHANGED` path,
adding each `ADDED` value, removing each `REMOVED` value — gives a value equal to the right one (when no value was cut
and nothing was ignored).

A diff MAY take two stored values with equal canonical hashes ([13](#13-canonical-value-hash)) as equal without reading
them. The hash isn't collision-resistant against an adversary; with `verifySkippedSubtrees` such values are compared.
`isEqual` never takes equal hashes as proof of equality.

## 10. JSONPath

### 10.1 Queries

A query is an RFC 9535 JSONPath query, the whole grammar: name, wildcard, index and slice selectors, several
selectors in one segment, descendant segments (`..`), and filters with comparisons, existence tests, `!`, `&&`, `||`,
parentheses, relative (`@`) and absolute (`$`) queries, and the functions of the RFC's registry: `length`, `count`,
`match`, `search` and `value`. The errata that order the alternatives of `function-argument` and `comparable` apply.

A query is compiled once (`JsonPath.compile`) and can then be used any number of times. Compiling finds every syntax
and type error of RFC 9535 §2.4.3 and fails with `InvalidJsonPathException`, an `IllegalArgumentException`, naming the
character offset. It also
enforces these limits:

| Limit | Value |
|---|---|
| characters of a query | 65 536 |
| nesting of filters, parentheses and function calls | 64 |
| selectors | 4 096 |
| a number literal | as a document's number ([2.2](#22-numbers)): its exponent MUST fit a 32-bit signed integer |

The regular expressions of `match` and `search` are I-Regexp (RFC 9485). A pattern that isn't valid I-Regexp makes
the function false; it isn't a compile error, since a pattern can come from the document. Groups nest at most 64
deep, an implementation limit RFC 9485 allows; a deeper pattern isn't valid. Outside a character class,
`^` and `$` anchor the start and the end of the string, as the RFC 9535 compliance test suite expects, although
RFC 9485's grammar reads them as ordinary characters.

### 10.2 Evaluation

Evaluation follows RFC 9535 §2, with the values and equality of [2](#2-values-and-equality): numbers compare by
decimal value (`1 == 1.0`), strings by code points, objects ignoring member order, arrays element by element, and
values of different types are never equal. `<`, `<=`, `>` and `>=` order numbers by value and strings by code
points; for anything else they're false. An object's members are visited in insertion order.

In a store with normalization ([2.1](#21-strings)), the names and string literals of a query, `match` and `search`
patterns included, are normalized before it runs.

Evaluating a compiled query never fails because of the query, with three exceptions that keep evaluation bounded; all
throw `QueryLimitException`:

- a `match` or `search` whose regular expression takes more than 16 000 000 steps (characters read, counting every
  re-read while backtracking);
- a `match` or `search` on a string of more than 16 MiB of UTF-8;
- a `match` or `search` whose regular expression runs out of stack, which the JDK's matcher can do on a long string
  (it recurses once per repetition of a group: `(a|b)*` on a string of about 100 000 characters).

Memory is otherwise independent of the size of the documents and of the values selected.

### 10.3 Select

`select(nodeRef, query)` evaluates a query on the value of one stored node, which is `$` for the query. The result is
the RFC 9535 nodelist: the selected nodes in the RFC's order, with duplicates (`$[0,0]` selects the first element
twice). Each node comes with its **Normalized Path** (RFC 9535 §2.7, written as in [9.3](#93-differences)) from the
node the query started at, and can be read as JSON ([5](#5-output)).

A select sees the operations acknowledged before it started, like a search ([8](#8-consistency)), and its nodes can be
read until its result is closed. A document that doesn't exist, isn't committed or was deleted fails with
`DocumentNotFoundException`; a `pre` beyond the document's last node with `IllegalArgumentException`.

### 10.4 Search

A JSONPath search evaluates a query on the root of every document of the store. Its hits are the **distinct selected
nodes in `(docId, pre)` order**: each node once, in document order, or in the reverse order with `DESCENDING`
([4.4](#44-order)). This is the one intentional difference from RFC 9535, whose nodelist can repeat a node and orders
nodes by how the query visits them; select ([10.3](#103-select)) returns the exact nodelist of one value.

| Field | Meaning | Default |
|---|---|---|
| `path` | the compiled query | — |
| `mode` | `CONTAINING_ELEMENT`: each selected node is a hit; `WHOLE_DOCUMENT`: each document where the query selects a node, with the root as the hit | `CONTAINING_ELEMENT` |
| `nested`, `limit`, `maxBytes`, `after`, `order` | as in [4.1](#41-query); `OUTERMOST_ONLY` drops a selected node inside another selected node | as there |
| `type` | only selected objects of this type are hits; in `WHOLE_DOCUMENT` mode a document is a hit when one of them is | none |
| `scan` | `REJECT` or `ALLOW_SCAN`, see below | `REJECT` |
| `documentFilter` | only documents whose root passes this filter are searched ([10.5](#105-document-filters)) | none |

Hits can be any value, not only objects; pagination, cursors and consistency are those of [4.6](#46-pagination) and
[8](#8-consistency). A hit's `path` is its node's Normalized Path from the document root ([3.2](#32-nodes)), where a
select's paths start at the node it started at.

**The index.** A search reads only the documents whose postings ([4.2](#42-matching)) have the terms the query
requires. A term is required when every node the query selects implies it:

- every selected node passes every segment, so the requirements of the segments all hold;
- a segment requires something only when each of its selectors is a filter that does, and then one of their
  requirements holds;
- in a filter, `&&` requires what either side requires, and `||` requires one of the two sides' requirements when both
  have one; `!`, `!=`, existence tests and functions require nothing;
- `q == literal` (either way round) requires the term `(name, literal)`, where the singular query `q` ends in `name` or
  in `name` and an index;
- `q < literal`, `<=`, `>` and `>=` with a number or string literal require one of the terms of `name` whose value
  lies on that side of the literal. A string range needs a store that indexes strings of every length.

When each segment before the filter a search looks up is one name or one index, the objects that filter tests are
children of a node at a fixed path, and postings whose path hash ([15](#15-path-hashes)) is not that path's are left
out: a document whose matching objects all sit elsewhere isn't read at all. `explain` reports the path as `within`.

A term that the store's index controls ([4.1](#41-query)) leave out requires nothing. A range that more than 1 048 576
documents satisfy fails the search with `QueryRejectedException` (`RANGE_TOO_WIDE`), or requires nothing when a scan
is allowed.

A query that requires no term fails with `QueryRejectedException` (`NEEDS_SCAN`) unless `scan` is `ALLOW_SCAN`, which evaluates it
on every visible document. `explain(query)` tells without running the search whether it would scan, and which terms
it would look up.

A search evaluates queries of at most 31 segments: a longer one fails with `QueryRejectedException`
(`TOO_MANY_SEGMENTS`) before anything is read. Select ([10.3](#103-select)) takes a query of any length.

### 10.5 Document filters

A document filter is an RFC 9535 `logical-expr`, the expression a filter selector holds after its `?`, with blank
space allowed around it. It is compiled once (`DocumentFilter.compile`), with the errors and limits of a query
([10.1](#101-queries)); the filter counts as one level of nesting. A search with a filter evaluates it once on each
candidate document with **both `@` and `$` standing for the document's root**, and searches only the documents for
which it is true: `@.kind == 'order' && @.version >= 2`. RFC 9535 has no way to test the root itself, since a filter
selector tests the children of a node; this is the one place where `@` is the root. Evaluation is that of
[10.2](#102-evaluation), with the store's normalization applied to the filter's names and strings.

A filter changes which documents are searched, nothing else: hits, their order, modes, cursors and `count` are those
of the same search over the documents it accepts.

The filter's requirement ([10.4](#104-search)) is ANDed with the query's, so a filter can make a search that would
need a scan a planned one, and `explain` shows the combined terms. The comparisons `@.name == literal` and
`@.name[i] == literal` (or with `$`) name terms whose containing object is the root. When the filter can only be true
with one of them true (`&&` needs it of one side, `||` of every side), a document without a posting of those terms on
its root is skipped without being read.

### 10.6 Templates

A template is a query (`JsonPathTemplate.compile`) or a document filter (`DocumentFilterTemplate.compile`) in which
a **placeholder** `${name}` stands for one literal. This is an extension of RFC 9535, which has no placeholders, and
`JsonPath.compile` and `DocumentFilter.compile` reject them. `${` is no RFC 9535 syntax outside a string literal, so
a placeholder can't be read as part of a query; inside a string literal it is text.

- A placeholder may stand wherever the grammar allows a `literal`: an operand of a comparison, or a function argument
  of ValueType, such as the pattern of `match`. Anywhere else, such as a selector or a test on its own, it is a
  compile error.
- A name is an ASCII letter or `_` followed by ASCII letters, digits and `_`. One name may appear more than once.
- Compiling finds every error and enforces every limit of [10.1](#101-queries) on the template's text.

Binding a template gives each placeholder a value: a string, a number, `true`, `false` or `null`. Every placeholder
needs a value, and a value for a name the template doesn't use is an `IllegalArgumentException`, as is JSON text,
which has no literal form. The value becomes a literal in the compiled query; it is never parsed, so no value can
change the query's structure.

The bound query is the query that compiling the **bound text** gives: the template's text with each placeholder
replaced by its value's literal. A string's literal is single-quoted, escaped as in a Normalized Path
([9.3](#93-differences)), and a number's literal is its decimal text, with an exponent when it has one (`1E+3`). The
bound text is the bound query's text, so the two are equal. A value that makes the bound text invalid fails the
binding with `InvalidJsonPathException`, naming the offset in the bound text: a string with an unpaired surrogate, a
number whose exponent is out of range ([10.1](#101-queries)), or bound text longer than 65 536 characters.

---

# Part 2 — Canonical forms

These byte forms are stored in indexes and fragment data. Changing any of them changes stored data, so they're
versioned with the storage format.

## 11. Varints

Unsigned LEB128, at most 10 bytes; the 10th byte MUST be `0x00` or `0x01`. Encodings MUST be minimal: a final byte
of `0x00` after the first byte is invalid. Readers reject invalid encodings as corruption.

## 12. Number keys

A number key encodes a JSON number so that unsigned byte-wise comparison of keys is numeric comparison, and equal
numbers have identical keys.

With the normalized form of [2.2](#22-numbers), regroup the digits in base 100: `value = ±0.p₁p₂…pₘ × 100^E`, where
`p₁ ≠ 0` and `pₘ ≠ 0` are pairs of digits (0–99). If `e` is odd, a zero digit is prepended so that `E = (e + 1) / 2`;
otherwise `E = e / 2`. If the digit count is then odd, a zero digit is appended.

```
zero       02
positive   03 | u32be(E + 2³¹) | (2·p + 1)… | 00
negative   01 | ~u32be(E + 2³¹) | ~(2·p + 1)… | FF
```

`~` inverts every bit. Examples: `1` → `03 80000001 03 00`; `123` → `03 80000002 03 2f 00`; `-0.05` → `01 7fffffff f4 ff`.

## 13. Canonical value hash

A 128-bit hash of a JSON value, equal for equal values ([2](#2-values-and-equality)). `H` is XXH3-128 with seed 0.
Inside hash inputs, a 128-bit value is 16 bytes: the high 64 bits big-endian, then the low 64 bits big-endian;
counts `n` are 64-bit little-endian.

```
null            H(01)
false / true    H(02) / H(03)
number          H(10 | number key)
string          H(20 | UTF-8 bytes)
array           a₀ = 0;  aᵢ = H('A' | aᵢ₋₁ | h(elementᵢ));  h = H('a' | aₙ | n)
object          m = Σ H('M' | varint(keyLength) | key | h(value))  mod 2¹²⁸;  h = H('O' | m | n)
```

`'A'`, `'a'`, `'M'`, `'O'` are the ASCII bytes. The object sum makes the hash independent of member order. The hash
isn't collision-resistant against an adversary; searches and `isEqual` confirm every hash match by comparing the
values, and a diff does when asked ([9.3](#93-differences)).

## 14. Index terms

The index key for a member `key: value`:

```
term := varint(keyLength) | key | tag | valueKey
```

| tag | Value | valueKey |
|---|---|---|
| `01` / `02` / `03` | null / false / true | empty |
| `10` | number whose key is at most 64 bytes | the number key |
| `11` | number whose key is longer | first 16 bytes of the number key, then its canonical hash |
| `20` | string of at most 64 bytes | the UTF-8 bytes |
| `21` | longer string | first 16 bytes of the UTF-8, then its canonical hash |
| `30` / `31` | object / array | the canonical hash |

Tags `11`, `21`, `30` and `31` are lossy: a match is confirmed against the stored value.

## 15. Path hashes

A path hash tells where a container sits by the member keys on the way down from the root, with every array index
written as the same step: in `{"a":[{"b":{}}, {"b":{}}]}` both inner objects have the path `a`, element, `b`. `h` is
an unsigned 32-bit state, `k` a key's UTF-8 bytes (normalized in a store that normalizes, [2.1](#21-strings)), and
`XXH3-64(input, seed)` is taken modulo 2³²:

```
root           ROOT = 0x2F2F2F2F
member key k   member(h, k) = XXH3-64(k, 0x4B · 2³² + h)
array element  element(h)   = XXH3-64(empty, 0x41 · 2³² + h)
no container   NONE = 0
```

Every posting of an index term ([14](#14-index-terms)) stores the path hash of its containing object's **parent**,
modulo 2¹⁶ (little-endian); the parent of the root is NONE. A search ([10.4](#104-search)) whose query fixes that path
skips postings with another value. Different paths can share a value, so what such a posting stands for is still
checked against the stored document.

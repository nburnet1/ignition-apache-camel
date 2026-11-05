# Jython / Camel collection interoperability investigation

This investigation changes no module runtime behavior and does not redeploy or
modify the running factory simulation. It tests whether the simulation's recursive
`py()` / `java()` helpers are necessary, and measures their cost separately from
the existing native `ignition-script` JSON boundary.

## Confirmed behavior

`JythonCamelInteropTest` exercises the actual SDK Jython and Camel 4.22.2:

- Java `Map` and `List` values already support Python indexing, `.get()`, `.items()`,
  `.update()`, `.append()` and iteration. Child Java collections retain their
  identity. A Java collection is not necessarily `isinstance(value, dict/list)`.
- `dict(java_map)` is an ordinary **shallow** Python copy; its children still refer
  to the original Java objects. A read-only Java map stays read-only through its
  native Jython view. Native mutation and shallow copying do not provide the
  isolation guarantees of a recursive copy.
- Python `dict` and `list` already implement Java's `Map` and `List` interfaces.
  Plain direct `Processor` callbacks, Camel splitting, and real Jackson
  marshal/unmarshal routes can consume them without these recursive helpers.
- Python `long` exposed as Java `Object` becomes `BigInteger`, even for values in
  Java's `long` range. Numeric value is retained, but Java boxed-type identity and
  `Map.equals()` are not interchangeable with Python numeric equality.
- Native access retains non-string keys and opaque Java objects and can access a
  cyclic map without traversing it. Recursive JSON-shaped copies do not have
  those semantics. In particular, `py()` stringifies keys and collapses `7` and
  `"7"` into one entry.

### Actual incompatibility: Camel Simple / bean caching

A Python dictionary's `hashCode()` raises `TypeError: unhashable type: 'dict'`.
Camel's bean-expression machinery constructs a `BeanInfoCacheKey` whose hash calls
the bean instance's `hashCode()`. Both flat `${body[enabled]}` and nested
`${body[order][quantity]}` expressions encounter this with a Python dictionary.
A shallow Java outer map is **not** sufficient if it contains an unhashable Python
dictionary child: Java map hashing traverses its entries.

The tests reproduce the failure and assert its cause. They do not install a
converter, override hashing, disable caching or patch Camel to hide it. Returning
an entirely Java collection tree from a callback works with these expressions;
returning a Python dictionary works in other tested consumers such as Jackson.
Therefore this is a downstream-consumer compatibility problem, not evidence that
Jython cannot read Java maps/lists.

Upstream source:
[Camel 4.22.2 BeanInfoCacheKey](https://github.com/apache/camel/blob/camel-4.22.2/components/camel-bean/src/main/java/org/apache/camel/component/bean/BeanInfoCacheKey.java).

### Existing JSON boundary has a different contract

`GatewayCamelServices.invoke` uses Java -> Gson tree -> Python and the reverse.
Tests of that exact conversion sequence confirm defensive header/input copies,
new containers on output, and signed 64-bit integer precision. They also reveal
that `TypeUtilities.gsonToPy` turns an integer such as `2**100 + 1` into a Python
float before the function runs, losing precision. Direct native interop and
Camel/Jackson retain that integer. Moving all callbacks to `ignition-script` is
therefore **not** a semantics-free or allocation-free optimization.

No existing boundary contract was changed as part of this investigation.

## Reproduce correctness tests

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
./gradlew :gateway:test --tests '*JythonCamelInteropTest'
```

Tests cover native Java views and identity, shallow copies, read-only semantics,
Python containers as Java interfaces, direct callbacks, Simple compatibility,
splitting, Jackson serialization, null/Boolean/Unicode/nested values, signed
Int64 limits, larger integers, non-string keys, cycles, and the original helpers.

## Reproduce performance measurements

```sh
./gradlew :gateway:jmhJar
"$JAVA_HOME/bin/java" -jar gateway/build/libs/gateway-0.2.0-SNAPSHOT-jmh.jar \
  JythonInteropBenchmark -prof gc -foe true \
  -rf json -rff gateway/build/jython-interop.json
```

The benchmark uses two forks, two one-second warmups and three one-second measured
iterations per fork; 256–512 MiB heaps; and payloads with 1, 100 and 1,000 order
lines. `gc.alloc.rate.norm` reports allocation in bytes per operation.

| Benchmark | Operation |
| --- | --- |
| `directJavaViewRead` | Read a nested quantity and list length directly through Jython's Java view. |
| `shallowDictRead` | Make a shallow `dict(payload)` and perform the same reads. |
| `nativeProcessor` | Real Jython `Processor` proxy returning `dict(payload, validated=True)`, with no recursive conversions. |
| `recursiveHelperProcessor` | Same transformation bracketed by the original `py()` / `java()` recursive helpers. |
| `existingJsonBoundaryConversions` | Existing `GatewayCamelServices.invoke` conversions with the same transformation, including header conversions. |

The last three perform the same domain transformation but have **different
ownership/type semantics**; a faster result is not permission to substitute one
contract for another. Native view access still allocates Python wrappers and
results: "no recursive copy" does not mean "zero allocation".

These are warmed callback/conversion measurements, **not** Gateway throughput or
publish-to-delivery latency. They exclude I/O, route handoffs, project lookup and
`ScriptManager.compileFunction`, and the native callback is not followed by the
known-incompatible Simple expression. Results are local and machine-dependent;
no existing repository performance baseline is overwritten.

### Local measured results

OpenJDK 17.0.21 on this macOS development host; two forks / six measured samples
per configuration. Cells are **microseconds per operation / bytes allocated per
operation**, rounded. Raw results: `gateway/build/jython-interop.json`.

| Operation | 1 line | 100 lines | 1,000 lines |
| --- | ---: | ---: | ---: |
| Direct Java-view read | 0.181 / 680 | 0.194 / 704 | 0.206 / 728 |
| Shallow `dict()` then read | 0.380 / 1,712 | 0.394 / 1,708 | 0.378 / 1,744 |
| Native callback + shallow transformed root | 0.631 / 1,856 | 0.603 / 1,856 | 0.575 / 1,776 |
| Callback with recursive `py()` / `java()` | 9.550 / 21,162 | 328.486 / 740,981 | 3,248.716 / 7,202,308 |
| Existing JSON boundary conversions + transform | 6.432 / 16,681 | 281.922 / 651,887 | 2,899.932 / 6,370,158 |

The native callback changes only the fixed-size root mapping and shares the
unmodified line list, so its cost does not grow with the number of lines. Both
recursive approaches walk/copy the entire payload, so their cost scales with it.
This is the useful finding, rather than the exact sub-microsecond differences
between the small native-view cases. Changing sharing/isolation behavior is still
a semantic decision, not just a performance optimization.

## Recommended next step

Keep native Java views for direct callbacks that only need collection access.
Do not blanket-convert inputs or move every callback through the JSON boundary.
Before removing output conversions, decide how to address the explicitly
reproduced Simple/bean-cache incompatibility. Until then the simulation remains
unchanged rather than replacing its helpers with another hidden workaround.

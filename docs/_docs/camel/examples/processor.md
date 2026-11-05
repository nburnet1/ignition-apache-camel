---
title: Processor
---
# Processor

Add `Uppercase` to `camel_demo`. It replaces the message body with its uppercase
form:

```python
from org.apache.camel import Exchange, Processor


class Uppercase(Processor):
    def process(self, exchange):
        # type: (Exchange) -> None
        message = exchange.getMessage()
        message.setBody(unicode(message.getBody()).upper())
```

The [route builder](../routes/) uses it here:

```python
.process(Uppercase())
```

The input `"hello"` leaves the route as `"HELLO"`.

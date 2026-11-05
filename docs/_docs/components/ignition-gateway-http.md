---
title: ignition-gateway-http
---
# ignition-gateway-http

Expose a route as an authenticated JSON POST endpoint on the Gateway.

```yaml
- route:
    id: receive-inspection-reading
    from:
      uri: ignition-gateway-http:/v1/inspection/reading?httpMethodRestrict=POST
      steps:
        - setBody:
            simple: '${body[temperature]}'
        - convertBodyTo:
            type: java.lang.Double
        - to: ignition-tag:[default]Inspection/LastTemperatureC
        - setBody:
            constant: recorded
```

Create the writable Double memory tag first. POST `{"temperature":23.5}` to:

```text
https://<gateway>/system/integrations/v1/inspection/reading
```

The response is the JSON string `"recorded"`.

The consumer path is exact; the saved integration name is **not** added to it.
Validate permitted ranges before using incoming values for equipment control.

## Access and limits

- In the integration’s **Settings → HTTP access** section, select explicit allowed security levels. None selected denies all callers.
- Authenticate with a native API key or Gateway session authorized for those levels. Browser sessions also require CSRF protection. Callers do not need configuration access to invoke the endpoint.
- Use `Content-Type: application/json`. JSON bodies arrive decoded; no JSON unmarshal step is needed.
- Request and response bodies are limited to **64 KiB**. Busy routes can return **503**.
- Supports POST only, exact paths, and JSON—not uploads, streaming, or wildcard paths.

The final body becomes the response. Set `CamelHttpResponseCode` to choose the response status; the default is 200. Client-supplied headers and credentials are not forwarded into the exchange.

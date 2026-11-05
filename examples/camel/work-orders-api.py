# Gateway project script examples. Not Designer/Client scripting scope.
# These same calls are available through CamelExamples.work_orders helper functions.

NAME = "camel-example-work-orders"


def submit_example():
    return system.camel.request(NAME, "direct:submit-orders", {
        "id": "WO-API-2001",
        "location": "lathe-01",
        "quantity": "20",
        "sku": "widget-A",
        "custom": {"priority": "high"},
    })


def submit_in_only(orders):
    system.camel.send(NAME, "direct:submit-orders", orders)


def complete_example():
    return system.camel.request(NAME, "direct:complete-order", {
        "id": "WO-API-2001", "location": "lathe-01",
    })


def snapshot_example():
    return system.camel.request(NAME, "direct:work-order-snapshot", {})


def status_example():
    return system.camel.status(NAME)

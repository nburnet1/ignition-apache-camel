"""Jython authoring alternative for the normalize-temperature Kamelet resource."""


def temperature(builder):
    template = builder.template()
    template.templateParameter("unit")
    template.templateParameter("machine")
    template.templateParameter("project", "CamelExamples")
    template.templateParameter("warningC", "80")
    (builder.from_("kamelet:source")
        .setHeader("kameletInputUnit").constant("{{unit}}")
        .setHeader("kameletMachine").constant("{{machine}}")
        .setHeader("kameletWarningC").constant("{{warningC}}")
        .to("ignition-script:kamelets.normalize?project={{project}}")
        .to("kamelet:sink"))

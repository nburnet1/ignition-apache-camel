package com.noahburnette.ignition.apachecamel.gateway;

/** Immutable definition reference for read-only analysis; never returned directly over HTTP. */
record CamelConfigurationSnapshot(String kind, String name, boolean enabled, String state, Object config) { }

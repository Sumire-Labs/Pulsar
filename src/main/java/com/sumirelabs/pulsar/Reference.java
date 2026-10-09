package com.sumirelabs.pulsar;

/**
 * Tags storage class. Values are populated at compile time by TokenEnvoy from Git and
 * gradle.properties.
 */
public final class Reference {
    public static final String MOD_ID = "@{mod_id}";
    public static final String MOD_NAME = "@{mod_name}";
    public static final String VERSION = "@{mod_version}";
    private Reference() {
    }
}

package {{package}};

/**
 * Tags storage class. Values are populated at build time by Blossom from
 * gradle.properties.
 */
public final class Reference {
    public static final String MOD_ID = "{{ mod_id }}";
    public static final String MOD_NAME = "{{ mod_name }}";
    public static final String VERSION = "{{ mod_version }}";
    private Reference() {
    }
}

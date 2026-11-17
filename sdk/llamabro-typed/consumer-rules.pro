# llamabro-typed uses no reflection: serializers come from the serialization plugin's generated code, and
# kotlinx-serialization ships the keep rules for @Serializable classes. It needs no consumer keep rules; the minified
# acceptance build decodes a typed value at start to prove it (sdk/acceptance/backends MainActivity, F28).

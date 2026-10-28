package io.github.connellite.model;

public record SchemaField(
        String path,
        String xsdType,
        boolean required,
        boolean collection,
        SchemaFacets facets
) {

    public SchemaField {
        facets = facets == null ? SchemaFacets.NONE : facets;
    }

    public SchemaField(String path, String xsdType, boolean required, boolean collection) {
        this(path, xsdType, required, collection, SchemaFacets.NONE);
    }
}

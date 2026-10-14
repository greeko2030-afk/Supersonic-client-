package net.kdt.pojavlaunch.modloaders.modpacks.models;

import org.jetbrains.annotations.Nullable;

/**
 * Search filters, passed to APIs
 */
public class SearchFilters {
    public boolean isModpack;
    public String name;
    @Nullable public String mcVersion;
    /** Modrinth category such as "adventure" or "technology" (null = any) */
    @Nullable public String category;
    /** Modrinth sort index: relevance, downloads, newest (null = relevance) */
    @Nullable public String sortIndex;

}

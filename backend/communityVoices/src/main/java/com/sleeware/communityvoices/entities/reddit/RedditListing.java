package com.sleeware.communityvoices.entities.reddit;

import java.util.List;

public record RedditListing(
        String after,
        List<RedditPost> children) {
}
package com.sleeware.communityvoices.entities.reddit;

import java.util.List;

public record RedditApiListingData(
        String after,
        List<RedditApiChild> children) {
}

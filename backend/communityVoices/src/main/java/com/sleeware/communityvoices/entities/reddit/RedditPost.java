package com.sleeware.communityvoices.entities.reddit;

import java.time.Instant;

public record RedditPost(
        String id,
        String title,
        String author,
        String selftext,
        Instant createdAt,
        Integer ups,
        Integer downs,
        Integer view_count,
        Integer score,
        Integer num_comments
){}

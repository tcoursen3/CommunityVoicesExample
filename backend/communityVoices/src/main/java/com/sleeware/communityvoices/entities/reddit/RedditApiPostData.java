package com.sleeware.communityvoices.entities.reddit;

public record RedditApiPostData(
        String id,
        String name,
        String title,
        String author,
        String selftext,
        double created_utc,
        Integer ups,
        Integer downs,
        Integer view_count,
        Integer score,
        Integer num_comments
){}

package com.sleeware.communityvoices.entities.reddit;


import com.sleeware.communityvoices.jobs.ScrapeCommunityJob;

public record RedditApiResponse(
    RedditApiListingData data) {
}
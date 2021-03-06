package org.commcare.android.tasks.network;

import org.commcare.android.net.HttpRequestGenerator;
import org.commcare.android.tasks.DataPullTask;

import java.io.IOException;

/**
 * Delegates data pulling requests to allow for modularity in data sources
 *
 * @author Phillip Mates (pmates@dimagi.com).
 */
public interface DataPullRequester {
    /**
     * Makes a data pulling request and returns an object for locally caching
     * the response data.
     *
     * @param task      For reporting data download progress
     * @param requestor For making the data pulling request
     * @return Instance to handle the response data
     */
    RemoteDataPullResponse makeDataPullRequest(DataPullTask task,
                                               HttpRequestGenerator requestor,
                                               String server,
                                               boolean includeSyncToken) throws IOException;
}

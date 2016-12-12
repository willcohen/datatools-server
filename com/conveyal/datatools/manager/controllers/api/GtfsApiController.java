package com.conveyal.datatools.manager.controllers.api;

import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.ObjectListing;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import com.conveyal.datatools.manager.DataManager;
import com.conveyal.datatools.manager.persistence.FeedStore;
import com.conveyal.datatools.manager.jobs.FeedUpdater;
import com.conveyal.gtfs.api.ApiMain;
import com.conveyal.gtfs.api.Routes;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Created by landon on 4/12/16.
 */
public class GtfsApiController {
    public static final Logger LOG = LoggerFactory.getLogger(GtfsApiController.class);
    public static String feedBucket;
    public static FeedUpdater feedUpdater;
    private static AmazonS3Client s3 = new AmazonS3Client();
    public static ApiMain gtfsApi;
    public static String bucketFolder;
    public static void register (String apiPrefix) throws IOException {

        // store list of GTFS feed eTags here
        Map<String, String> eTagMap = new HashMap<>();

        // uses bucket, folder, and local cache according to main app config
        gtfsApi.initialize(DataManager.gtfsCache);

        // check for use of extension...
        String extensionType = DataManager.getConfigPropertyAsText("modules.gtfsapi.use_extension");
        switch (extensionType) {
            case "mtc":
                LOG.info("Using extension " + extensionType + " for service alerts module");
                feedBucket = DataManager.getConfigPropertyAsText("extensions." + extensionType + ".s3_bucket");
                bucketFolder = DataManager.getConfigPropertyAsText("extensions." + extensionType + ".s3_download_prefix");

                // Adds feeds on startup
                eTagMap.putAll(registerS3Feeds(null, feedBucket, bucketFolder));
                break;
            default:
                LOG.warn("No extension provided for GTFS API");
                // use application s3 bucket and s3Prefix
                if ("true".equals(DataManager.getConfigPropertyAsText("application.data.use_s3_storage"))) {
                    feedBucket = DataManager.getConfigPropertyAsText("application.data.gtfs_s3_bucket");
                    bucketFolder = FeedStore.s3Prefix;
                }
                else {
                    feedBucket = null;
                }
                break;
        }

        // check for update interval (in seconds) and initialize feedUpdater
        JsonNode updateFrequency = DataManager.getConfigProperty("modules.gtfsapi.update_frequency");
        if (updateFrequency != null) {
            feedUpdater = new FeedUpdater(eTagMap, 0, updateFrequency.asInt());
        }

        // set gtfs-api routes with apiPrefix
        Routes.routes(apiPrefix);
    }

    public static Map<String, String> registerS3Feeds (Map<String, String> eTags, String bucket, String dir) {
        if (eTags == null) {
            eTags = new HashMap<>();
        }
        Map<String, String> newTags = new HashMap<>();
        // iterate over feeds in download_prefix folder and register to gtfsApi (MTC project)
        ObjectListing gtfsList = s3.listObjects(bucket, dir);
        for (S3ObjectSummary objSummary : gtfsList.getObjectSummaries()) {

            String eTag = objSummary.getETag();
            if (!eTags.containsValue(eTag)) {
                String keyName = objSummary.getKey();

                // don't add object if it is a dir
                if (keyName.equals(dir)){
                    continue;
                }
                String filename = keyName.split("/")[1];
                String feedId = filename.replace(".zip", "");
                try {
                    LOG.warn("New version found for " + keyName + " is null. Downloading from s3...");
                    S3Object object = s3.getObject(bucket, keyName);
                    InputStream in = object.getObjectContent();
                    byte[] buf = new byte[1024];
                    File file = new File(FeedStore.basePath, filename);
                    OutputStream out = new FileOutputStream(file);
                    int count;
                    while( (count = in.read(buf)) != -1)
                    {
                        if( Thread.interrupted() )
                        {
                            throw new InterruptedException();
                        }
                        out.write(buf, 0, count);
                    }
                    out.close();
                    in.close();

                    // delete old mapDB files
                    String[] dbFiles = {".db", ".db.p"};
                    for (String type : dbFiles) {
                        File db = new File(FeedStore.basePath, feedId + type);
                        db.delete();
                    }
                    newTags.put(feedId, eTag);

                    // initiate load of feed source into API with get call
                    gtfsApi.getFeedSource(feedId);
                } catch (Exception e) {
                    LOG.warn("Could not load feed " + keyName, e);
                }
            }
        }
        return newTags;
    }
}

package com.dotcms.publisher.business;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.StatefulJob;

import com.dotcms.enterprise.publishing.PublishDateUpdater;
import com.dotcms.publisher.business.PublishAuditStatus.Status;
import com.dotcms.publisher.endpoint.bean.PublishingEndPoint;
import com.dotcms.publisher.endpoint.business.PublishingEndPointAPI;
import com.dotcms.publisher.pusher.PushPublisher;
import com.dotcms.publisher.pusher.PushPublisherConfig;
import com.dotcms.publisher.util.PublisherUtil;
import com.dotcms.publisher.util.TrustFactory;
import com.dotcms.publishing.DotPublishingException;
import com.dotmarketing.business.APILocator;
import com.dotmarketing.exception.DotDataException;
import com.dotmarketing.util.Config;
import com.dotmarketing.util.Logger;
import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.api.client.config.ClientConfig;
import com.sun.jersey.api.client.config.DefaultClientConfig;
import com.sun.jersey.client.urlconnection.HTTPSProperties;

/**
 * This class read the publishing_queue table and send bundles to some endpoints
 * @author Alberto
 *
 */
public class PublisherQueueJob implements StatefulJob {

	private PublishAuditAPI pubAuditAPI = PublishAuditAPI.getInstance();
	private PublishingEndPointAPI endpointAPI = APILocator.getPublisherEndPointAPI();
	private PublisherAPI pubAPI = PublisherAPI.getInstance();

    public static final Integer MAX_NUM_TRIES = Config.getIntProperty( "PUBLISHER_QUEUE_MAX_TRIES", 3 );

    @SuppressWarnings("rawtypes")
	public void execute(JobExecutionContext arg0) throws JobExecutionException {
		try {
		    Logger.debug(PublisherQueueJob.class, "Started PublishQueue Job - check for publish dates");
		    PublishDateUpdater.updatePublishExpireDates(arg0.getFireTime());
		    Logger.debug(PublisherQueueJob.class, "Finished PublishQueue Job - check for publish/expire dates");

			Logger.debug(PublisherQueueJob.class, "Started PublishQueue Job - Audit update");
			updateAuditStatus();
			Logger.debug(PublisherQueueJob.class, "Finished PublishQueue Job - Audit update");


			List<PublishingEndPoint> endpoints = endpointAPI.getEnabledReceivingEndPoints();

			if(endpoints != null && endpoints.size() > 0)  {
				Logger.debug(PublisherQueueJob.class, "Started PublishQueue Job");
				PublisherAPI pubAPI = PublisherAPI.getInstance();

				PushPublisherConfig pconf = new PushPublisherConfig();
				List<Class> clazz = new ArrayList<Class>();
				clazz.add(PushPublisher.class);

				List<Map<String,Object>> bundles = pubAPI.getQueueBundleIdsToProcess();
				List<PublishQueueElement> tempBundleContents = null;
				PublishAuditStatus status = null;
				PublishAuditHistory historyPojo = null;
				String tempBundleId = null;

				for(Map<String,Object> bundle: bundles) {
					Date publishDate = (Date) bundle.get("publish_date");

					if(publishDate.before(new Date())) {
						tempBundleId = (String)bundle.get("bundle_id");
						tempBundleContents = pubAPI.getQueueElementsByBundleId(tempBundleId);

						//Setting Audit objects
						//History
						historyPojo = new PublishAuditHistory();
						//Retriving assets
						Map<String, String> assets = new HashMap<String, String>();
						List<PublishQueueElement> assetsToPublish = new ArrayList<PublishQueueElement>(); // all assets but contentlets

						for(PublishQueueElement c : tempBundleContents) {
							assets.put((String) c.getAsset(), c.getType());
							if(!c.getType().equals("contentlet"))
								assetsToPublish.add(c);
						}
						historyPojo.setAssets(assets);

						// all types of assets in the queue but contentlets are passed here, which are passed through lucene queries
						pconf.setAssets(assetsToPublish);

						//Status
						status =  new PublishAuditStatus(tempBundleId);
						status.setStatusPojo(historyPojo);

						//Insert in Audit table
						pubAuditAPI.insertPublishAuditStatus(status);

						//Queries creation
						pconf.setLuceneQueries(PublisherUtil.prepareQueries(tempBundleContents));
						pconf.setId(tempBundleId);
						pconf.setUser(APILocator.getUserAPI().getSystemUser());
						pconf.setStartDate(new Date());
						pconf.runNow();

						pconf.setPublishers(clazz);
//						pconf.setEndpoints(endpoints);

                        if ( Integer.parseInt( bundle.get( "operation" ).toString() ) == PublisherAPI.ADD_OR_UPDATE_ELEMENT ) {
                            pconf.setOperation( PushPublisherConfig.Operation.PUBLISH );
                        } else {
                            pconf.setOperation( PushPublisherConfig.Operation.UNPUBLISH );
                        }

                        try {
                            APILocator.getPublisherAPI().publish( pconf );
                        } catch ( DotPublishingException e ) {
                            /*
                            If we are getting errors creating the bundle we should stop trying to publish it, this is not just a connection error,
                            there is something wrong with a bundler or creating the bundle.
                             */
                            Logger.error( PublisherQueueJob.class, "Unable to publish Bundle: " + e.getMessage(), e );
                            pubAuditAPI.updatePublishAuditStatus( pconf.getId(), PublishAuditStatus.Status.FAILED_TO_BUNDLE, historyPojo );
                            pubAPI.deleteElementsFromPublishQueueTable( pconf.getId() );
                        }
                    }

				}

				Logger.debug(PublisherQueueJob.class, "Finished PublishQueue Job");
			}

		} catch (NumberFormatException e) {
			Logger.error(PublisherQueueJob.class,e.getMessage(),e);
		} catch (DotDataException e) {
			Logger.error(PublisherQueueJob.class,e.getMessage(),e);
		} catch (DotPublisherException e) {
			Logger.error(PublisherQueueJob.class,e.getMessage(),e);
		} catch (Exception e) {
			Logger.error(PublisherQueueJob.class,e.getMessage(),e);
		}

	}


	private void updateAuditStatus() throws DotPublisherException, DotDataException {
		ClientConfig clientConfig = new DefaultClientConfig();
		TrustFactory tFactory = new TrustFactory();

		if(Config.getStringProperty("TRUSTSTORE_PATH") != null && !Config.getStringProperty("TRUSTSTORE_PATH").trim().equals("")) {
				clientConfig.getProperties()
				.put(HTTPSProperties.PROPERTY_HTTPS_PROPERTIES, new HTTPSProperties(tFactory.getHostnameVerifier(), tFactory.getSSLContext()));
		}
        Client client = Client.create(clientConfig);
        WebResource webResource = null;

        List<PublishAuditStatus> pendingAudits = pubAuditAPI.getPendingPublishAuditStatus();

        //Foreach Bundle
        for(PublishAuditStatus pendingAudit: pendingAudits) {
        	//Gets groups list
        	PublishAuditHistory localHistory = pendingAudit.getStatusPojo();

        	Map<String, Map<String, EndpointDetail>> endpointsMap = localHistory.getEndpointsMap();
        	Map<String, EndpointDetail> endpointsGroup = null;

        	Map<String, Map<String, EndpointDetail>> bufferMap = new HashMap<String, Map<String, EndpointDetail>>();
        	//Foreach Group
        	for(String group : endpointsMap.keySet()) {
        		endpointsGroup = endpointsMap.get(group);

	        	//Foreach endpoint
	        	for(String endpointId: endpointsGroup.keySet()) {
	        		EndpointDetail localDetail = endpointsGroup.get(endpointId);

	        		if(localDetail.getStatus() != PublishAuditStatus.Status.SUCCESS.getCode() &&
	        			localDetail.getStatus() != PublishAuditStatus.Status.FAILED_TO_PUBLISH.getCode())
	        		{
		        		PublishingEndPoint target = endpointAPI.findEndPointById(endpointId);

		        		if(target != null) {
			        		webResource = client.resource(target.toURL()+"/api/auditPublishing");

			        		try {
					        	PublishAuditHistory remoteHistory =
					        			PublishAuditHistory.getObjectFromString(
					        			webResource
								        .path("get")
								        .path(pendingAudit.getBundleId()).get(String.class));

					        	if(remoteHistory != null) {
					        		bufferMap.putAll(remoteHistory.getEndpointsMap());
						        	break;
					        	}
			        		} catch(Exception e) {
			        			Logger.error(PublisherQueueJob.class,e.getMessage(),e);
			        		}
		        		}
	        		}
	        		else if(localDetail.getStatus() == PublishAuditStatus.Status.SUCCESS.getCode() ){
	        			Map<String, EndpointDetail> m = new HashMap<String, EndpointDetail>();
	        			m.put(endpointId, localDetail);
	        			bufferMap.put(group, m);
	        		}
		        }
	        }

            int countOk = 0;
            int countPublishing = 0;
        	for(String groupId: bufferMap.keySet()) {
        		Map<String, EndpointDetail> group = bufferMap.get(groupId);

        		boolean isGroupOk = false;
        		boolean isGroupPublishing = false;
	        	for(String endpoint: group.keySet()) {
	        		EndpointDetail detail = group.get(endpoint);
	        		localHistory.addOrUpdateEndpoint(groupId, endpoint, detail);
	        		if(detail.getStatus() == Status.SUCCESS.getCode())
	        			isGroupOk = true;
	        		else if(detail.getStatus() == Status.PUBLISHING_BUNDLE.getCode())
	        			isGroupPublishing = true;

	        	}

	        	if(isGroupOk)
	        		countOk++;

	        	if(isGroupPublishing)
	        		countPublishing++;
        	}

        	if(countOk == endpointsGroup.size()) {
	        	pubAuditAPI.updatePublishAuditStatus(pendingAudit.getBundleId(),
	        			PublishAuditStatus.Status.SUCCESS,
	        			localHistory);
	        	pubAPI.deleteElementsFromPublishQueueTable(pendingAudit.getBundleId());
        	} else if(localHistory.getNumTries() >= MAX_NUM_TRIES) {
        		pubAuditAPI.updatePublishAuditStatus(pendingAudit.getBundleId(),
	        			PublishAuditStatus.Status.FAILED_TO_PUBLISH,
	        			localHistory);
        		pubAPI.deleteElementsFromPublishQueueTable(pendingAudit.getBundleId());
        	} else if(countPublishing == endpointsGroup.size()){
        		pubAuditAPI.updatePublishAuditStatus(pendingAudit.getBundleId(),
        				PublishAuditStatus.Status.PUBLISHING_BUNDLE,
	        			localHistory);
        	} else {
        		pubAuditAPI.updatePublishAuditStatus(pendingAudit.getBundleId(),
        				PublishAuditStatus.Status.WAITING_FOR_PUBLISHING,
	        			localHistory);
        	}
        }

	}
}

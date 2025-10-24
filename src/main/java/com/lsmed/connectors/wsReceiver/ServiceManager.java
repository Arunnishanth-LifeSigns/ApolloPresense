package com.lsmed.connectors.wsReceiver;

import java.util.HashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.lsmed.connectors.wsReceiver.endpoint.MedAlgoProcessor;
import com.lsmed.connectors.wsReceiver.endpoint.PresenseProcessor;

/**
 * 
 *
 */
public class ServiceManager {

	public final static String SERVICE_ARRHYTHMIA = "arrhythmia";

	public final static String PROVIDER_MEDICAL_ALGO = "medalgo";
	
	/**
	 * Singleton instance
	 */
	private static ServiceManager mgr = null;

	
	/**
	 * LOGGER
	 */
	private static final Logger LOG = LogManager.getLogger(ServiceManager.class.getSimpleName());
	
	/**
	 * HashMap holds the Providers for each Service type
	 */
	HashMap <String, HashMap <String, IPatchStreamProcessor>> serviceMap = 
			new HashMap<String, HashMap <String, IPatchStreamProcessor>>();

	/**
	 * Default Service Providers for each Service type
	 */
	HashMap <String, IPatchStreamProcessor> defaultMap = 
			new HashMap<String, IPatchStreamProcessor>();
	

	/**
	 * Initializes the available Services and Providers
	 */
	private ServiceManager () {

		// TODO: Create new instance for each thread instead of common one.
		defaultMap.put(SERVICE_ARRHYTHMIA, new MedAlgoProcessor());
		
		HashMap<String, IPatchStreamProcessor> predictionProviders = new HashMap<>();
        predictionProviders.put("presense", new PresenseProcessor());
        serviceMap.put("prediction", predictionProviders); 
	}

	
	/**
	 * Returns Singleton instance
	 * @return
	 */
	public static ServiceManager getInstance() {
		
		if (mgr != null)
			return mgr;
		
		mgr = new ServiceManager();
		return mgr;
	}

	
	/**
	 * 
	 * @param serviceId
	 * @return
	 */
	public IPatchStreamProcessor getDefaultProvider(String serviceId) {
		
		return defaultMap.get(serviceId);
	}

	/**
	 * 
	 * @param serviceId
	 * @param providerId
	 * @return
	 */
	public IPatchStreamProcessor getMappedProvider(String serviceId, String providerId) {

		IPatchStreamProcessor provider;
		String providerName;

		if (providerId == null) {
			provider = getDefaultProvider(serviceId);
			providerName = "default";
		} else {
			HashMap <String, IPatchStreamProcessor> map = serviceMap.get(serviceId);
			if (map == null) {
				provider = getDefaultProvider(serviceId);
				providerName = "default (fallback)";
			} else {
				provider = map.get(providerId);
				providerName = providerId;
			}
		}
		LOG.info("Service assigned: {} with provider: {}", serviceId, providerName);
		return provider;
	}
}

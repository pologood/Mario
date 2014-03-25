/**
 *    Copyright 2014 Renren.com
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package com.renren.Wario;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;

import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.renren.Wario.config.ConfigLoader;
import com.renren.Wario.mailsender.IMailSender;
import com.renren.Wario.msgsender.IMsgSender;
import com.renren.Wario.plugin.IPlugin;
import com.renren.Wario.zookeeper.ZooKeeperClient;
import com.renren.Wario.zookeeper.ZooKeeperCluster;

public class WarioMain extends Thread {

	private static Logger logger = LogManager.getLogger(WarioMain.class
			.getName());

	private final String pluginPackage = "com.renren.Wario.plugin.";
	private final String msgSenderPackage = "com.renren.Wario.msgsender.";
	private final String mailSenderPackage = "com.renren.Wario.mailsender.";
	private final String pluginPathPrefix;

	private ConfigLoader configLoader = null;
	// <zooKeeperName, cluster>
	private Map<String, ZooKeeperCluster> clusters = null;
	// <pluginName, <ZooKeeperName, context> >
	private final Map<String, Map<String, byte[]>> contexts;

	public WarioMain() {
		if (System.getProperty("default.plugin.path") == null) {
			pluginPathPrefix = "./plugins/";
		} else {
			pluginPathPrefix = System.getProperty("default.plugin.path");
		}
		configLoader = ConfigLoader.getInstance();
		clusters = new HashMap<String, ZooKeeperCluster>();
		contexts = new TreeMap<String, Map<String, byte[]>>();
	}

	public void init() {
		configLoader.loadConfig();
		clusters.clear();
		updateServerConfig(configLoader.getServerObjects());
	}

	@Override
	public void run() {
		while (!isInterrupted()) {

			configLoader.loadConfig();

			Set<String> uselessClusters = WarioUtilTools
					.getDifference(clusters.keySet(), configLoader
							.getServerObjects().keySet());
			Iterator<String> it = uselessClusters.iterator();
			while (it.hasNext()) {
				String zooKeeperName = it.next();
				ZooKeeperCluster cluster = clusters.get(zooKeeperName);
				cluster.close();
				clusters.remove(zooKeeperName);
			}

			Set<String> uselessContexts = WarioUtilTools
					.getDifference(contexts.keySet(), configLoader
							.getPluginObjects().keySet());
			contexts.entrySet().removeAll(uselessContexts);

			updateServerConfig(configLoader.getServerObjects());

			try {
				sleep(10 << 10);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}

			runPlugins();
		}
	}

	private void updateServerConfig(Map<String, JSONObject> serverObjects) {
		ZooKeeperCluster cluster = null;

		Iterator<Entry<String, JSONObject>> it = serverObjects.entrySet()
				.iterator();
		while (it.hasNext()) {
			Map.Entry<String, JSONObject> entry = (Map.Entry<String, JSONObject>) it
					.next();

			String zookeeperName = entry.getKey();
			JSONObject object = entry.getValue();

			if (!clusters.containsKey(zookeeperName)) {
				cluster = new ZooKeeperCluster(zookeeperName, object);
				try {
					cluster.init();
				} catch (JSONException e) {
					logger.error(zookeeperName + " init failed! "
							+ e.toString());
				}
				clusters.put(zookeeperName, cluster);
			} else {
				cluster = clusters.get(zookeeperName);
				try {
					cluster.updateClients(object);
				} catch (JSONException e) {
					logger.error(zookeeperName + " update failed! "
							+ e.toString());
				}
			}
		}
	}

	/**
	 * Run plugins on every cluseter.
	 */
	private void runPlugins() {
		Iterator<Entry<String, JSONArray>> it = configLoader.getPluginObjects()
				.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<String, JSONArray> entry = it.next();

			String pluginName = entry.getKey();
			JSONArray arrary = entry.getValue();
			if (!contexts.containsKey(pluginName)) {
				contexts.put(pluginName, new TreeMap<String, byte[]>());
			}

			for (int i = 0; i < arrary.length(); ++i) {
				try {
					JSONObject object = arrary.getJSONObject(i);
					if("ObserverPlugin".equals(pluginName)) {
						processObserverPlugin(pluginName, object);
					} else {
						processPlugin(pluginName, object);
					}
				} catch (JSONException e) {
					logger.error("Failed to process json string : "
							+ pluginName + " " + i + "th line. " + e.toString());
				}
			}
		}
	}

	/**
	 * Process the plugin on every client of the cluster except the observer.
	 * @param pluginName
	 * @param object
	 * @throws JSONException
	 */
	private void processPlugin(String pluginName, JSONObject object)
			throws JSONException {
		String zooKeeperName = object.getString("zooKeeperName");
		ZooKeeperCluster cluster = null;
		if (clusters.containsKey(zooKeeperName)) {
			cluster = clusters.get(zooKeeperName);
		} else {
			logger.error("Wrong zooKeeperName! " + zooKeeperName);
			return;
		}
		if (!contexts.get(pluginName).containsKey(zooKeeperName)) {
			contexts.get(pluginName).put(zooKeeperName, new byte[1 << 20]);  // 1M
		}

		Iterator<Entry<String, ZooKeeperClient>> it = cluster.getClients()
				.entrySet().iterator();

		while (it.hasNext()) {
			Map.Entry<String, ZooKeeperClient> entry = it.next();
			ZooKeeperClient client = entry.getValue();
			byte[] context = contexts.get(pluginName).get(zooKeeperName);
			try {
				IPlugin plugin = createPlugin(pluginName, object, client,
						context);
				plugin.run();
				logger.info(pluginName + " runs at "
						+ client.getConnectionString() + " successfully!");
			} catch (Exception e) {
				logger.error(pluginName + " runs at "
						+ client.getConnectionString() + " failed! "
						+ e.toString());
			}
		}
	}
	
	/**
	 * Process ObserverPlugin on the observer client.
	 * @param pluginName
	 * @param object
	 * @throws JSONException
	 */
	private void processObserverPlugin(String pluginName, JSONObject object)
			throws JSONException {
		String zooKeeperName = object.getString("zooKeeperName");
		ZooKeeperCluster cluster = null;
		if (clusters.containsKey(zooKeeperName)) {
			cluster = clusters.get(zooKeeperName);
		} else {
			logger.error("Wrong zooKeeperName! " + zooKeeperName);
			return;
		}
		if (!contexts.get(pluginName).containsKey(zooKeeperName)) {
			contexts.get(pluginName).put(zooKeeperName, new byte[1 << 20]);  // 1M
		}

		ZooKeeperClient client = cluster.getObserverClient();
		byte[] context = contexts.get(pluginName).get(zooKeeperName);
		try {
			IPlugin plugin = createPlugin(pluginName, object, client,
					context);
			plugin.run();
			logger.info(pluginName + " runs at "
					+ client.getConnectionString() + " successfully!");
		} catch (Exception e) {
			logger.error(pluginName + " runs at "
					+ client.getConnectionString() + " failed! "
					+ e.toString());
		}
	}

	private IPlugin createPlugin(String pluginName, JSONObject object,
			ZooKeeperClient client, byte[] context)
			throws InstantiationException, IllegalAccessException,
			ClassNotFoundException, MalformedURLException, JSONException {
		IPlugin plugin = null;
		String msgSenderName = object.getString("msgSender");
		String mailSenderName = object.getString("mailSender");
		JSONArray array = object.getJSONArray("args");

		URL pluginUrl = new File(pluginPathPrefix + File.separator + pluginName
				+ ".jar").toURI().toURL();
		URL msgSenderUrl = new File(pluginPathPrefix + File.separator
				+ msgSenderName + ".jar").toURI().toURL();
		URL mailSenderUrl = new File(pluginPathPrefix + File.separator
				+ mailSenderName + ".jar").toURI().toURL();
		URL[] urls = new URL[] { pluginUrl, msgSenderUrl, mailSenderUrl };

		ClassLoader classLoader = new URLClassLoader(urls);
		plugin = (IPlugin) classLoader.loadClass(pluginPackage + pluginName)
				.newInstance();
		plugin.msgSender = (IMsgSender) classLoader.loadClass(
				msgSenderPackage + msgSenderName).newInstance();
		plugin.mailSender = (IMailSender) classLoader.loadClass(
				mailSenderPackage + mailSenderName).newInstance();
		plugin.client = client;
		plugin.clusterContext = context;
		
		ArrayList<String> args = new ArrayList<String>();
		args.clear();
		for (int i = 0; i < array.length(); i++) {
			args.add(array.getString(i));
		}
		plugin.args = new String[args.size()];
		plugin.args = args.toArray(plugin.args);
		return plugin;
	}
}

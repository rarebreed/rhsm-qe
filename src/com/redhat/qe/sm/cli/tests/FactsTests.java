package com.redhat.qe.sm.cli.tests;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.testng.SkipException;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import com.redhat.qe.auto.tcms.ImplementsNitrateTest;
import com.redhat.qe.auto.testng.Assert;
import com.redhat.qe.auto.testng.LogMessageUtil;
import com.redhat.qe.auto.testng.TestNGUtils;
import com.redhat.qe.sm.base.ConsumerType;
import com.redhat.qe.sm.base.SubscriptionManagerCLITestScript;
import com.redhat.qe.sm.cli.tasks.CandlepinTasks;
import com.redhat.qe.sm.cli.tasks.SubscriptionManagerTasks;
import com.redhat.qe.sm.data.SubscriptionPool;
import com.redhat.qe.tools.RemoteFileTasks;
import com.redhat.qe.tools.SSHCommandResult;
import com.redhat.qe.tools.SSHCommandRunner;



/**
 * @author jsefler
 */
@Test(groups={"facts"})
public class FactsTests extends SubscriptionManagerCLITestScript{
	
	
	// Test Methods ***********************************************************************

	@Test(	description="subscription-manager: facts --update (when not registered)",
			groups={"blockedByBug-654429"},
			enabled=true)
	//@ImplementsNitrateTest(caseId=)
	public void FactsUpdateWhenNotRegistered_Test() {
		
		// make sure we are not registered
		clienttasks.unregister(null, null, null);
		
		log.info("Assert that one must be registered to update the facts...");
		for (Boolean list : new Boolean[]{true,false}) {			
			SSHCommandResult result = clienttasks.facts_(list, true, null, null, null);
			Assert.assertEquals(result.getStdout().trim(),consumerNotRegisteredMsg,
				"One must be registered to update the facts.");
		}
	}
	
	
	@Test(	description="subscription-manager: facts --list (when not registered)",
			groups={"blockedByBug-654429","blockedByBug-661329","blockedByBug-666544"},
			enabled=true)
	//@ImplementsNitrateTest(caseId=)
	public void FactsListWhenNotRegistered_Test() {
		
		// make sure we are not registered
		clienttasks.unregister(null, null, null);
		
		log.info("Assert that one need not be registered to list the facts...");		
		SSHCommandResult result = clienttasks.facts(true, false, null, null, null);
		Assert.assertContainsNoMatch(result.getStderr(),consumerNotRegisteredMsg,
				"One need not be registered to list the facts.");
		Assert.assertContainsNoMatch(result.getStdout(),consumerNotRegisteredMsg,
				"One need not be registered to list the facts.");
	}
	
	
	@Test(	description="subscription-manager: facts (without --list or --update)",
			groups={"blockedByBug-654429"},
			enabled=true)
	//@ImplementsNitrateTest(caseId=)
	public void FactsWithoutListOrUpdate_Test() {
		
		log.info("Assert that one need one must specify --list or --update...");		
		SSHCommandResult result = clienttasks.facts_(false, false, null, null, null);
		Assert.assertEquals(result.getExitCode(), Integer.valueOf(255),
				"exitCode from the facts without --list or --update");
		Assert.assertEquals(result.getStdout().trim(),neededOptionMsg,
				"stdout from facts without --list or --update");
	}
	
	@Test(	description="subscription-manager: facts and rules: consumer facts list",
			groups={}, dependsOnGroups={},
			dataProvider="getClientsData",
			enabled=true)
	@ImplementsNitrateTest(caseId=56386)
	public void ConsumerFactsList_Test(SubscriptionManagerTasks smt) {
		
		// start with fresh registrations using the same clientusername user
		smt.unregister(null, null, null);
		smt.register(clientusername, clientpassword, null, null, null, null, null, null, null, null);
		
		// list the system facts
		smt.facts(true, false, null, null, null);
	}
	
	
	@Test(	description="subscription-manager: facts and rules: fact check RHEL distribution",
			groups={"blockedByBug-666540"}, dependsOnGroups={},
			enabled=true)
	@ImplementsNitrateTest(caseId=56329)
	public void FactCheckRhelDistribution_Test() {
		
		// skip if client1 and client2 are not a Server and Workstation distributions
		SSHCommandRunner workClient = null,servClient = null;
		SubscriptionManagerTasks workClientTasks = null, servClientTasks = null;
		if (client1!=null && client1tasks.getRedhatRelease().startsWith("Red Hat Enterprise Linux Workstation")) {
			workClient = client1; workClientTasks = client1tasks;
		}
		if (client2!=null && client2tasks.getRedhatRelease().startsWith("Red Hat Enterprise Linux Workstation")) {
			workClient = client2; workClientTasks = client2tasks;
		}
		if (client1!=null && client1tasks.getRedhatRelease().startsWith("Red Hat Enterprise Linux Server")) {
			servClient = client1; servClientTasks = client1tasks;
		}
		if (client2!=null && client2tasks.getRedhatRelease().startsWith("Red Hat Enterprise Linux Server")) {
			servClient = client2; servClientTasks = client2tasks;
		}
		if (workClient==null || servClient==null) {
			throw new SkipException("This test requires a RHEL Workstation client and a RHEL Server client.");
		}
		
		// start with fresh registrations using the same clientusername user
		workClientTasks.unregister(null, null, null);
		servClientTasks.unregister(null, null, null);
		workClientTasks.register(clientusername, clientpassword, null, null, null, null, null, null, null, null);
		servClientTasks.register(clientusername, clientpassword, null, null, null, null, null, null, null, null);
		

		// get all the pools available to each client
		List<SubscriptionPool> workClientPools = workClientTasks.getCurrentlyAvailableSubscriptionPools();
		List<SubscriptionPool> servClientPools = servClientTasks.getCurrentlyAvailableSubscriptionPools();
		
		log.info("Verifying that the pools available to the Workstation consumer are not identitcal to those available to the Server consumer...");
		Assert.assertTrue(!workClientPools.containsAll(servClientPools) || !servClientPools.containsAll(workClientPools),
				"Because the facts of a system client running RHEL Workstation versus RHEL Server should be different, the available subscription pools to these two systems should not be the same.");

		// FIXME TODO Verify with development that these are valid asserts
		//log.info("Verifying that the pools available to the Workstation consumer do not contain Server in the ProductName...");
		//log.info("Verifying that the pools available to the Server consumer do not contain Workstation in the ProductName...");

	}
	
	@Test(	description="subscription-manager: facts and rules: check sockets",
			groups={}, dependsOnGroups={},
			dataProvider="getClientsData",
			enabled=true)
	//@ImplementsTCMS(id="")
	public void AssertPoolsWithSocketsGreaterThanSystemsCpuSocketAreNotAvailable_Test(SubscriptionManagerTasks smt) throws Exception {
		smt.unregister(null, null, null);
		String consumerId = smt.getCurrentConsumerId(smt.register(clientusername,clientpassword,null,null,null,null, null, null, null, null));
		String ownerKey = CandlepinTasks.getOwnerKeyOfConsumerId(serverHostname, serverPort, serverPrefix, clientusername, clientpassword, consumerId);
		
		boolean foundPoolWithSocketAttributes = false;
		boolean conclusiveTest = false;
		
		// get all the pools available to each client
		List<SubscriptionPool> clientPools = smt.getCurrentlyAvailableSubscriptionPools();
		
		// get the number of cpu_sockets for this system consumer
		String factName = "cpu.cpu_socket(s)";
		int systemValue = Integer.valueOf(smt.getFactValue(factName));
		log.info(factName+" for this system consumer: "+systemValue);
		
		// loop through the owner's subscriptions
		JSONArray jsonSubscriptions = new JSONArray(CandlepinTasks.getResourceUsingRESTfulAPI(serverHostname,serverPort,serverPrefix,clientusername,clientpassword,"/owners/"+ownerKey+"/subscriptions"));	
		for (int i = 0; i < jsonSubscriptions.length(); i++) {
			JSONObject jsonSubscription = (JSONObject) jsonSubscriptions.get(i);
			String poolId = jsonSubscription.getString("id");
			JSONObject jsonProduct = (JSONObject) jsonSubscription.getJSONObject("product");
			String subscriptionName = jsonProduct.getString("name");
			String productId = jsonProduct.getString("id");
			JSONArray jsonAttributes = jsonProduct.getJSONArray("attributes");
			// loop through the attributes of this subscription looking for the "sockets" attribute
			for (int j = 0; j < jsonAttributes.length(); j++) {
				JSONObject jsonAttribute = (JSONObject) jsonAttributes.get(j);
				String attributeName = jsonAttribute.getString("name");
				if (attributeName.equals("sockets")) {
					// found the sockets attribute - get its value
					foundPoolWithSocketAttributes = true;
					int poolValue = jsonAttribute.getInt("value");
					
					// assert that if the maximum cpu_sockets for this subscription pool is greater than the cpu_sockets facts for this consumer, then this product should NOT be available
					log.fine("Maximum sockets for this subscriptionPool name="+subscriptionName+": "+poolValue);
					SubscriptionPool pool = new SubscriptionPool(productId,poolId);
					if (poolValue < systemValue) {
						Assert.assertFalse(clientPools.contains(pool), "Subscription Pool "+pool+" IS NOT available since this system's "+factName+" ("+systemValue+") exceeds the maximum ("+poolValue+") for this pool to be a candidate for availability.");
						conclusiveTest = true;
					} else {
						log.info("Subscription Pool "+pool+" may or may not be available depending on other facts besides "+factName+".");
					}
					break;
				}
			}
		}
		if (jsonSubscriptions.length()==0) {
			log.warning("No owner subscriptions were found for a system registered by '"+clientusername+"' and therefore we could not attempt this test.");
			throw new SkipException("No owner subscriptions were found for a system registered by '"+clientusername+"' and therefore we could not attempt this test.");		
		}
		if (!conclusiveTest) {
			//log.warning("The facts for this system did not allow us to perform a conclusive test.");
			throw new SkipException("The facts for this system did not allow us to perform a conclusive test.");
		}
		Assert.assertTrue(foundPoolWithSocketAttributes,"At least one Subscription Pools was found for which we could attempt this test.");
	}
	
	@Test(	description="subscription-manager: facts and rules: check arch",
			groups={}, dependsOnGroups={},
			dataProvider="getClientsData",
			enabled=true)
	//@ImplementsTCMS(id="")
	public void AssertPoolsWithAnArchDifferentThanSystemsArchitectureAreNotAvailable_Test(SubscriptionManagerTasks smt) throws Exception {
		smt.unregister(null, null, null);
		String consumerId = smt.getCurrentConsumerId(smt.register(clientusername,clientpassword,null,null,null,null, null, null, null, null));
		String ownerKey = CandlepinTasks.getOwnerKeyOfConsumerId(serverHostname, serverPort, serverPrefix, clientusername, clientpassword, consumerId);

		boolean foundPoolWithArchAttributes = false;
		boolean conclusiveTest = false;
		
		// get all the pools available to this client
		List<SubscriptionPool> clientPools = smt.getCurrentlyAvailableSubscriptionPools();
		
		// get the number of cpu_sockets for this system consumer
		String factName = "cpu.architecture";
		String systemValue = smt.getFactValue(factName);
		log.info(factName+" for this system consumer: "+systemValue);
		
		// loop through the owner's subscriptions
		JSONArray jsonSubscriptions = new JSONArray(CandlepinTasks.getResourceUsingRESTfulAPI(serverHostname,serverPort,serverPrefix,clientusername,clientpassword,"/owners/"+ownerKey+"/subscriptions"));	
		for (int i = 0; i < jsonSubscriptions.length(); i++) {
			JSONObject jsonSubscription = (JSONObject) jsonSubscriptions.get(i);
			String poolId = jsonSubscription.getString("id");
			JSONObject jsonProduct = (JSONObject) jsonSubscription.getJSONObject("product");
			String subscriptionName = jsonProduct.getString("name");
			String productId = jsonProduct.getString("id");
			JSONArray jsonAttributes = jsonProduct.getJSONArray("attributes");
			// loop through the attributes of this subscription looking for the "sockets" attribute
			for (int j = 0; j < jsonAttributes.length(); j++) {
				JSONObject jsonAttribute = (JSONObject) jsonAttributes.get(j);
				String attributeName = jsonAttribute.getString("name");
				if (attributeName.equals("arch")) {
					// found the arch attribute - get its value
					foundPoolWithArchAttributes = true;
					String poolValue = jsonAttribute.getString("value");
					
					// assert that if the maximum cpu_sockets for this subscription pool is greater than the cpu_sockets facts for this consumer, then this product should NOT be available
					log.fine("Arch for this subscriptionPool name="+subscriptionName+": "+poolValue);
					SubscriptionPool pool = new SubscriptionPool(productId,poolId);
					if (!poolValue.equalsIgnoreCase(systemValue) && !poolValue.equalsIgnoreCase("ALL")) {
						Assert.assertFalse(clientPools.contains(pool), "Subscription Pool "+pool+" IS NOT available since this system's "+factName+" ("+systemValue+") does not match ("+poolValue+") for this pool to be a candidate for availability.");
						conclusiveTest = true;
					} else {
						log.info("Subscription Pool "+pool+" may or may not be available depending on other facts besides "+factName+".");
					}
					break;
				}
			}
		}
		if (jsonSubscriptions.length()==0) {
			log.warning("No owner subscriptions were found for a system registered by '"+clientusername+"' and therefore we could not attempt this test.");
			throw new SkipException("No owner subscriptions were found for a system registered by '"+clientusername+"' and therefore we could not attempt this test.");		
		}
		if (!conclusiveTest) {
			log.warning("The facts for this system did not allow us to perform a conclusive test.");
			throw new SkipException("The facts for this system did not allow us to perform a conclusive test.");
		}
		Assert.assertTrue(foundPoolWithArchAttributes,"At least one Subscription Pools was found for which we could attempt this test.");
	}
	
	@Test(	description="subscription-manager: facts and rules: bypass rules due to type",
			groups={"blockedByBug-641027"}, dependsOnGroups={},
			enabled=true)
	@ImplementsNitrateTest(caseId=56331)
	public void BypassRulesDueToType_Test() throws JSONException {
		// determine which client is a RHEL Workstation
		SSHCommandRunner client = null;
		SubscriptionManagerTasks clienttasks = null;
		if (client1!=null && client1tasks.getRedhatRelease().startsWith("Red Hat Enterprise Linux Workstation")) {
			client = client1; clienttasks = client1tasks;
		} else if (client2!=null && client2tasks.getRedhatRelease().startsWith("Red Hat Enterprise Linux Workstation")) {
			client = client2; clienttasks = client2tasks;
		} else {
			throw new SkipException("This test requires a RHEL Workstation client.");
		}

		// on a RHEL workstation register to candlepin (as type system)
		clienttasks.unregister(null, null, null);
		clienttasks.register(clientusername, clientpassword, ConsumerType.system, null, null, null, null, null, null, null);

		// get a list of available pools and all available pools (for this system consumer)
		List<SubscriptionPool> compatiblePoolsAsSystemConsumer = clienttasks.getCurrentlyAvailableSubscriptionPools();
		List<SubscriptionPool> allPoolsAsSystemConsumer = clienttasks.getCurrentlyAllAvailableSubscriptionPools();
		
		Assert.assertFalse(compatiblePoolsAsSystemConsumer.containsAll(allPoolsAsSystemConsumer),
				"Without bypassing the rules, not *all* pools are available for subscribing by a type=system consumer.");
		Assert.assertTrue(allPoolsAsSystemConsumer.containsAll(compatiblePoolsAsSystemConsumer),
				"The pools available to a type=system consumer is a subset of --all --available pools.");
		
		// now register to candlepin (as type candlepin)
		clienttasks.unregister(null, null, null);
		clienttasks.register(clientusername, clientpassword, ConsumerType.candlepin, null, null, null, null, null, null, null);

		// get a list of available pools and all available pools (for this candlepin consumer)
		List<SubscriptionPool> compatiblePoolsAsCandlepinConsumer = clienttasks.getCurrentlyAvailableSubscriptionPools();
		List<SubscriptionPool> allPoolsAsCandlepinConsumer = clienttasks.getCurrentlyAllAvailableSubscriptionPools();

		Assert.assertTrue(compatiblePoolsAsCandlepinConsumer.containsAll(allPoolsAsCandlepinConsumer) && allPoolsAsCandlepinConsumer.containsAll(compatiblePoolsAsCandlepinConsumer),
				"The pools available to a type=candlepin consumer bypass the rules (list --all --available is identical to list --available).");
	
		// now assert that all the pools can be subscribed to by the consumer (registered as type candlepin)
		clienttasks.subscribeToAllOfTheCurrentlyAvailableSubscriptionPools(ConsumerType.candlepin);
	}
	
	

	
	

	
	// TODO Candidates for an automated Test:
	//		https://bugzilla.redhat.com/show_bug.cgi?id=669513
	//		https://bugzilla.redhat.com/show_bug.cgi?id=664847#2
	//		https://bugzilla.redhat.com/show_bug.cgi?id=629670

	
	
	
	// Configuration Methods ***********************************************************************

	
	
	
	// Protected Methods ***********************************************************************

	protected String consumerNotRegisteredMsg = "Consumer not registered. Please register using --username and --password";
	protected String neededOptionMsg = "Error: Need either --list or --update, Try facts --help";
	

	
	
	// Data Providers ***********************************************************************
	
	@DataProvider(name="getClientsData")
	public Object[][] getClientsDataAs2dArray() {
		return TestNGUtils.convertListOfListsTo2dArray(getClientsDataAsListOfLists());
	}
	protected List<List<Object>> getClientsDataAsListOfLists(){
		List<List<Object>> ll = new ArrayList<List<Object>>();

		// SSHCommandRunner client
		if (client1!= null)	ll.add(Arrays.asList(new Object[]{client1tasks}));
		if (client2!= null)	ll.add(Arrays.asList(new Object[]{client2tasks}));

		return ll;
	}
	

}

package rhsm.cli.tests;

import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.xmlrpc.XmlRpcException;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.testng.SkipException;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterGroups;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeGroups;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import com.redhat.qe.Assert;
import com.redhat.qe.auto.bugzilla.BzChecker;
import com.redhat.qe.auto.tcms.ImplementsNitrateTest;
import com.redhat.qe.auto.testng.TestNGUtils;

import rhsm.base.CandlepinType;
import rhsm.base.ConsumerType;
import rhsm.base.SubscriptionManagerBaseTestScript;
import rhsm.base.SubscriptionManagerCLITestScript;
import rhsm.cli.tasks.CandlepinTasks;
import rhsm.cli.tasks.SubscriptionManagerTasks;
import rhsm.data.ConsumerCert;
import rhsm.data.ContentNamespace;
import rhsm.data.EntitlementCert;
import rhsm.data.InstalledProduct;
import rhsm.data.OrderNamespace;
import rhsm.data.ProductCert;
import rhsm.data.ProductSubscription;
import rhsm.data.Repo;
import rhsm.data.RevokedCert;
import rhsm.data.SubscriptionPool;
import rhsm.data.YumRepo;

import com.redhat.qe.tools.RemoteFileTasks;
import com.redhat.qe.tools.SSHCommandResult;
import com.redhat.qe.tools.SSHCommandRunner;

/**
 * @author skallesh
 * 
 * 
 */
@Test(groups = { "BugzillaTests","Tier3Tests" })
public class BugzillaTests extends SubscriptionManagerCLITestScript {
	protected String ownerKey="";
	protected String randomAvailableProductId = null;
	protected EntitlementCert expiringCert = null;
	protected String EndingDate;
	protected final String importCertificatesDir = "/tmp/sm-importExpiredCertificatesDir"
			.toLowerCase();
	protected final String myEmptyCaCertFile = "/etc/rhsm/ca/myemptycert.pem";
	protected Integer configuredHealFrequency = null;
	protected Integer configuredCertFrequency = null;
	protected String configuredHostname=null;
	protected String factname="system.entitlements_valid";
	protected String SystemDateOnClient=null;
	protected String SystemDateOnServer=null;
	List<String> providedProducts = new ArrayList<String>();
	protected List<File> entitlementCertFiles = new ArrayList<File>();
	protected final String importCertificatesDir1 = "/tmp/sm-importV1CertificatesDir".toLowerCase();


	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(	description="verify rhsm-debug --no-archive --destination <destination Loc> throws [Errno 18] Invalid cross-device link",
			groups={"VerifyrhsmDebugWithNoArchive","blockedByBug-1175284"},
			enabled=true)
	public void VerifyrhsmDebugWithNoArchive() throws Exception {
		String path = "/tmp/rhsmDebug/";
		client.runCommandAndWait("rm -rf "+path+" && mkdir -p "+path);	// pre cleanup
		clienttasks.register(sm_clientUsername, sm_clientPassword,
				sm_clientOrg, null, null, null, null, true, null, null,
				(String) null, null, null, null, true, null, null, null, null);
		SSHCommandResult result = client.runCommandAndWait(clienttasks.rhsmDebugSystemCommand(path, true, null, null, null, null, null, null));
		Assert.assertContainsMatch(result.getStdout(), "Wrote: "+path+"rhsm-debug-system");
		client.runCommandAndWait("rm -rf "+path);	// post cleanup
	}

	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(	description="verify subscription-manager attach --file <file> ,with file being empty attaches subscription for installed product",
			groups={"VerifyAttachingEmptyFile","blockedByBug-1175291"},
			enabled=true)
	public void VerifyAttachingEmptyFile() throws Exception {
		if (clienttasks.isPackageVersion("subscription-manager","<","1.13.8-1")) throw new SkipException("The attach --file function was not implemented in this version of subscription-manager.");
		String file = "/tmp/empty_file";
		clienttasks.register(sm_clientUsername, sm_clientPassword,
				sm_clientOrg, null, null, null, null, true, null, null,
				(String) null, null, null, null, true, null, null, null, null);
		clienttasks.autoheal(null, null, true, null, null, null);	// disable
		client.runCommandAndWait("touch "+ file);
		client.runCommandAndWait("cat "+ file);
		SSHCommandResult sshCommandResult = clienttasks.subscribe_(null,(String)null,(String)null,(String)null, null, null, null, null, file, null, null, null);
		String expectedStderr = String.format("Error: The file \"%s\" does not contain any pool IDs.",file);	// commit 1ec3ee950642b24e6b55a23db10e447bd0fada4f	// Bug 1175291 - subscription-manager attach --file <file> ,with file being empty attaches subscription for installed product
		Assert.assertEquals(sshCommandResult.getStderr().trim(), expectedStderr, "The stderr result from subscribe with an empty file of poolIds.");
	}

	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(	description="verify  Subscription-manager repos --list is deleting certificate imported on system",
			groups={"VerifyImportedCertgetsDeletedByRepoCommand","blockedByBug-1160150"},
			enabled=true)
	public void VerifyImportedCertgetsDeletedByRepoCommand() throws Exception {
		clienttasks.register(sm_clientUsername, sm_clientPassword,
				sm_clientOrg, null, null, null, null, true, null, null,
				(String) null, null, null, null, true, null, null, null, null);
		clienttasks.autoheal(null, null, true, null, null, null);	// disable
		SubscriptionPool pool = getRandomSubsetOfList(clienttasks.getCurrentlyAvailableSubscriptionPools(),1).get(0);
		File importEntitlementCertFile = clienttasks.subscribeToSubscriptionPool(pool,sm_clientUsername, sm_clientPassword,sm_serverUrl);
		File importEntitlementKeyFile = clienttasks.getEntitlementCertKeyFileCorrespondingToEntitlementCertFile(importEntitlementCertFile);
		File importCertificateFile = new File(importCertificatesDir1+File.separator+importEntitlementCertFile.getName());
		client.runCommandAndWait("mkdir -p "+importCertificatesDir1);
		client.runCommandAndWait("cat "+importEntitlementCertFile+" "+importEntitlementKeyFile+" > "+importCertificateFile);
		String path =importCertificateFile.getPath();
		clienttasks.clean(null, null, null);
		clienttasks.importCertificate(path);
		int Ceritificate_countBeforeRepoCommand=clienttasks.getCurrentEntitlementCertFiles().size();
		SSHCommandResult Result=clienttasks.repos_(true, null, null,(String)null, null, null, null, null);
		int Ceritificate_countAfterRepoCommand=clienttasks.getCurrentEntitlementCertFiles().size();
		Assert.assertEquals(Ceritificate_countBeforeRepoCommand, Ceritificate_countAfterRepoCommand);
		Assert.assertEquals(Result.getExitCode(), new Integer(0));
		Assert.assertContainsMatch(Result.getStdout().trim(), "Available Repositories in /etc/yum.repos.d/redhat.repo");
	}


	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(	description="verify End date and start date of the subscription is appropriate one when you attach a future subscription and then  heal after 1 min",
			groups={"VerifyStartEndDateOfSubscription","blockedByBug-994853"},
			enabled=true)
	public void VerifyStartEndDateOfSubscription() throws Exception {
		clienttasks.register(sm_clientUsername, sm_clientPassword,
				sm_clientOrg, null, null, null, null, true, null, null,
				(String) null, null, null, null, true, null, null, null, null);
		Map<String, String> factsMap = new HashMap<String, String>();
		factsMap.put("cpu.cpu_socket(s)", String.valueOf(4));
		clienttasks.createFactsFileWithOverridingValues(factsMap);
		clienttasks.facts(null, true, null, null, null);	// [jsefler] I believe facts --update should be called after overriding facts
		clienttasks.autoheal(null, null, true, null, null, null);
		for(SubscriptionPool AvailablePools:clienttasks.getCurrentlyAvailableSubscriptionPools()){
			if(AvailablePools.productId.equals("awesomeos-x86_64")){
				clienttasks.subscribe(null, null, AvailablePools.poolId, null, null, "1", null, null, null, null, null, null);
			}
		}
		InstalledProduct installedProduct = InstalledProduct.findFirstInstanceWithMatchingFieldFromList("productId", "100000000000002", clienttasks.getCurrentlyInstalledProducts());

		for(ProductSubscription consumedProductSubscription:clienttasks.getCurrentlyConsumedProductSubscriptions()){
			if (consumedProductSubscription.provides.contains(installedProduct.productName)) {
				Assert.assertTrue(!installedProduct.startDate.after(consumedProductSubscription.startDate), "Comparing Start Date '"+InstalledProduct.formatDateString(installedProduct.startDate)+"' of Installed Product '"+installedProduct.productName+"' to Start Date '"+InstalledProduct.formatDateString(consumedProductSubscription.startDate)+"' of Consumed Subscription '"+consumedProductSubscription.productName+"'.  (Installed Product startDate should be <= Consumed Subscription startDate)");
			}
		}

		clienttasks.autoheal(null, true, null, null, null, null);
		clienttasks.restart_rhsmcertd(null, null, true);

		InstalledProduct installedProductAfterRHSM = InstalledProduct.findFirstInstanceWithMatchingFieldFromList("productId", "100000000000002", clienttasks.getCurrentlyInstalledProducts());

		for(ProductSubscription consumedProductSubscription:clienttasks.getCurrentlyConsumedProductSubscriptions()){
			//Assert.assertEquals(installedProductAfterRHSM.startDate, consumedProductSubscription.startDate);
			//  [jsefler] was here...
			//^ that assertion only passes when all of the available pools were generated on the same day (as is the case for TESTDATA)
			//^ that assertion fails when a new subscription pool with a different start date that provides product "awesomeos-x86_64" has been added to the org.  This happens with test subscription 'An "Exempt SLA" service level subscription (matches all service levels)'
			//revised assertion should make sure the start date of the installed product is the oldest product subscription that covers this installed product...
			if (consumedProductSubscription.provides.contains(installedProductAfterRHSM.productName)) {
				Assert.assertTrue(!installedProductAfterRHSM.startDate.after(consumedProductSubscription.startDate), "Comparing Start Date '"+InstalledProduct.formatDateString(installedProductAfterRHSM.startDate)+"' of Installed Product '"+installedProductAfterRHSM.productName+"' to Start Date '"+InstalledProduct.formatDateString(consumedProductSubscription.startDate)+"' of Consumed Subscription '"+consumedProductSubscription.productName+"'.  (Installed Product startDate should be <= Consumed Subscription startDate)");
				if(!consumedProductSubscription.isActive){
					Assert.assertEquals(installedProductAfterRHSM.endDate, consumedProductSubscription.endDate);
				}
			}
		}
	}



	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(	description="verify status check and response from server after addition and deletion of product from /etc/pki/product/",
			groups={"VerifyStatusCheck","blockedByBug-921870","blockedByBug-1183175"},
			enabled=true)
	public void VerifyStatusCheck() throws Exception {
		String result,expectedStatus;
		Boolean Flag = false;
		ProductCert installedProductCert32060 = ProductCert.findFirstInstanceWithMatchingFieldFromList("productId", "32060", clienttasks.getCurrentProductCerts());
		Assert.assertNotNull(installedProductCert32060, "Found installed product cert 32060 needed for this test.");
		configureTmpProductCertDirWithInstalledProductCerts(Arrays.asList(new ProductCert[]{}));
		clienttasks.register_(sm_clientUsername, sm_clientPassword,
				sm_clientOrg, null, null, null, null, true, null, null,
				(String) null, null, null, null, true, false, null, null, null);
		Assert.assertTrue(clienttasks.getCurrentProductCertFiles().isEmpty(),"No product certs are installed.");
		result=clienttasks.status(null, null, null, null).getStdout();
		expectedStatus = "Overall Status: Current";
		Assert.assertTrue(result.contains(expectedStatus), "System status displays '"+expectedStatus+"' because no products are installed.");	
		client.runCommandAndWait("cp "+installedProductCert32060.file+" "+tmpProductCertDir);	// OR THIS IS VALID TOO configureTmpProductCertDirWithInstalledProductCerts(Arrays.asList(new ProductCert[]{installedProductCert32060}));
		result=clienttasks.status(null, null, null, null).getStdout();
		expectedStatus = "Overall Status: Invalid";
		Assert.assertTrue(result.contains(expectedStatus), "System status displays '"+expectedStatus+"' after manully installing a product cert.");
		for(SubscriptionPool pool : clienttasks.getCurrentlyAvailableSubscriptionPools()){
		if (CandlepinTasks.isPoolRestrictedToUnmappedVirtualSystems(sm_clientUsername, sm_clientPassword, sm_serverUrl, pool.poolId)) {
				Flag=true;
			}}
		clienttasks.autoheal(null, true, null, null, null, null);	// enable
		clienttasks.run_rhsmcertd_worker(true);
		result=clienttasks.status(null, null, null, null).getStdout();
		if(Flag){
			expectedStatus = "Overall Status: Insufficient";
			Assert.assertTrue(result.contains(expectedStatus), "System status displays '"+expectedStatus+"' after finally running rhsmcertd worker with auto-healing.");

		}
		else{
		expectedStatus = "Overall Status: Current";
		Assert.assertTrue(result.contains(expectedStatus), "System status displays '"+expectedStatus+"' after finally running rhsmcertd worker with auto-healing.");
	}}


	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(	description="verify if Status displays product name multiple times when the system had inactive stack subscriptions",
			groups={"VerifyIfStatusDisplaysProductNameMultipleTimes","blockedByBug-972752"},
			enabled=true)
	public void VerifyIfStatusDisplaysProductNameMultipleTimes() throws Exception {
		clienttasks.register(sm_clientUsername, sm_clientPassword,
				sm_clientOrg, null, null, null, null, null, null, null,
				(String) null, null, null, null, true, null, null, null, null);
		int sockets = 4;
		clienttasks.autoheal(null, null, true, null, null, null);
		Map<String, String> factsMap = new HashMap<String, String>();
		factsMap.put("cpu.cpu_socket(s)", String.valueOf(sockets));
		clienttasks.createFactsFileWithOverridingValues(factsMap);
		clienttasks.facts(null, true, null, null, null);
		for(SubscriptionPool AvailablePools:clienttasks.getCurrentlyAvailableSubscriptionPools()){
			if(AvailablePools.productId.equals("awesomeos-x86_64")){
				clienttasks.subscribe(null, null, AvailablePools.poolId, null, null, "1", null, null, null, null, null, null);
			}

		}
		String result=clienttasks.status(null, null, null, null).getStdout();
		clienttasks.autoheal(null, true, null, null, null, null);
	}

	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(	description="verify if update facts button won't recreate facts.json file",
			groups={"VerifyFactsFileExistenceAfterUpdate","blockedByBug-627707"},
			enabled=true)
	public void VerifyFactsFileExistenceAfterUpdate() throws Exception {
		clienttasks.register(sm_clientUsername, sm_clientPassword,
				sm_clientOrg, null, null, null, null, null, null, null,
				(String) null, null, null, null, true, null, null, null, null);
		client.runCommandAndWait("rm -rf "+clienttasks.rhsmFactsJsonFile);
		Assert.assertFalse(RemoteFileTasks.testFileExists(client, clienttasks.rhsmFactsJsonFile)==1, "rhsm facts json file '"+clienttasks.rhsmFactsJsonFile+"' exists");
		clienttasks.facts(null, true, null, null, null);
		Assert.assertTrue(RemoteFileTasks.testFileExists(client, clienttasks.rhsmFactsJsonFile)==1, "rhsm facts json file '"+clienttasks.rhsmFactsJsonFile+"' exists");

	}

	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(	description="verify if unsubscribe does not delete entitlement cert from location /etc/pki/entitlement/product for consumer type candlepin ",
			groups={"unsubscribeTheRegisteredConsumerTypeCandlepin","blockedByBug-621962"},
			enabled=true)
	public void unsubscribeTheRegisteredConsumerTypeCandlepin() throws Exception {
		clienttasks.register(sm_clientUsername, sm_clientPassword,
				sm_clientOrg, null, ConsumerType.candlepin, null, null, null, null, null,
				(String) null, null, null, null, true, null, null, null, null);
		clienttasks.subscribe(true, null,(String)null, null, null, null, null, null, null, null, null, null);
		List<File> files=clienttasks.getCurrentEntitlementCertFiles();
		Assert.assertNotNull(files.size());	
		clienttasks.unsubscribeFromAllOfTheCurrentlyConsumedProductSubscriptions();
		files=clienttasks.getCurrentEntitlementCertFiles();
		Assert.assertTrue(files.isEmpty());
	}


	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(	description="verify if you can unsubscribe from imported cert",
			groups={"unsubscribeImportedcert","blockedByBug-691784"},
			enabled=true)
	public void unsubscribeImportedcert() throws Exception {
		clienttasks.register(sm_clientUsername, sm_clientPassword,
				sm_clientOrg, null, null, null, null, null, null, null,
				(String) null, null, null, null, true, null, null, null, null);
		clienttasks.autoheal(null, null, true, null, null, null);	// disable
		SubscriptionPool pool = getRandomSubsetOfList(clienttasks.getCurrentlyAvailableSubscriptionPools(),1).get(0);
		File importEntitlementCertFile = clienttasks.subscribeToSubscriptionPool(pool,sm_clientUsername, sm_clientPassword,sm_serverUrl);
		File importEntitlementKeyFile = clienttasks.getEntitlementCertKeyFileCorrespondingToEntitlementCertFile(importEntitlementCertFile);
		File importCertificateFile = new File(importCertificatesDir1+File.separator+importEntitlementCertFile.getName());
		client.runCommandAndWait("mkdir -p "+importCertificatesDir1);
		client.runCommandAndWait("cat "+importEntitlementCertFile+" "+importEntitlementKeyFile+" > "+importCertificateFile);
		String path =importCertificateFile.getPath();
		clienttasks.clean(null, null, null);
		clienttasks.importCertificate(path);
		String result=clienttasks.unsubscribe(true,(BigInteger)null, null, null, null).getStdout();
		String expected_result="1 subscriptions removed from this system.";
		Assert.assertEquals(result.trim(), expected_result);
	}

	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(	description="verify if subscription manager CLI does not display all facts",
			groups={"SystemFactsInCLI","blockedByBug-722239"},
			enabled=true)
	public void SystemFactsInCLI() throws Exception {
		clienttasks.register(sm_clientUsername, sm_clientPassword,
				sm_clientOrg, null, null, null, null, null, null, null,
				(String) null, null, null, null, true, null, null, null, null);
		Map<String, String> result=clienttasks.getFacts("system");
		Assert.assertNotNull(result.get("system.certificate_version"));
		//	Assert.assertNotNull(result.get("system.name"));


	}

	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(	description="verify if Registering with an activation key which has run out of susbcriptions results in a system, but no identity certificate",
			groups={"RegisterWithActivationKeyWithExpiredPool","blockedByBug-803814"},
			enabled=true)
	public void RegisterWithActivationKeyWithExpiredPool() throws Exception {
		int endingMinutesFromNow = 1;
		Integer addQuantity=1;
		String name = String.format("%s_%s-ActivationKey%s", sm_clientUsername,
				sm_clientOrg, System.currentTimeMillis());
		Map<String, String> mapActivationKeyRequest = new HashMap<String, String>();
		mapActivationKeyRequest.put("name", name);
		JSONObject jsonActivationKeyRequest = new JSONObject(
				mapActivationKeyRequest);
		JSONObject jsonActivationKey = new JSONObject(
				CandlepinTasks.postResourceUsingRESTfulAPI(sm_clientUsername,
						sm_clientPassword, sm_serverUrl, "/owners/"
								+ sm_clientOrg + "/activation_keys",
								jsonActivationKeyRequest.toString()));

		clienttasks.register(sm_clientUsername, sm_clientPassword,
				sm_clientOrg, null, null, null, null, null, null, null,
				(String) null, null, null, null, true, null, null, null, null);
		String consumerId = clienttasks.getCurrentConsumerId();
		ownerKey = CandlepinTasks.getOwnerKeyOfConsumerId(sm_serverAdminUsername, sm_serverAdminPassword, sm_serverUrl, consumerId);
		Calendar endCalendar = new GregorianCalendar();
		String expiringPoolId = createTestPool(-60*24,endingMinutesFromNow);
		endCalendar.add(Calendar.MINUTE, endingMinutesFromNow);
		DateFormat yyyy_MM_dd_DateFormat = new SimpleDateFormat("M/d/yy h:mm aaa");
		String EndingDate=yyyy_MM_dd_DateFormat.format(endCalendar.getTime());
		sleep(endingMinutesFromNow*60*1000);
		new JSONObject(CandlepinTasks.postResourceUsingRESTfulAPI(sm_clientUsername, sm_clientPassword, sm_serverUrl, "/activation_keys/" + jsonActivationKey.getString("id") + "/pools/" +expiringPoolId+(addQuantity==null?"":"?quantity="+addQuantity), null));
		clienttasks.unregister(null, null, null);
		SSHCommandResult registerResult=clienttasks.register_(null, null, sm_clientOrg, null, null, null, null, null, null, null, name, null, null, null, true, null, null, null, null);			
		System.out.println(EndingDate);
		String expected_message="Unable to attach pool with ID '"+expiringPoolId+"'.: Subscriptions for "+randomAvailableProductId+" expired on: "+EndingDate+".";
		if (clienttasks.isVersion(servertasks.statusVersion, ">", "0.9.30-1")) expected_message =  "No activation key was applied successfully.";	// Follows: candlepin-0.9.30-1	// https://github.com/candlepin/candlepin/commit/bcb4b8fd8ee009e86fc9a1a20b25f19b3dbe6b2a
		Assert.assertEquals(registerResult.getStderr().trim(), expected_message);
		SSHCommandResult identityResult=clienttasks.identity_(null, null, null, null, null, null, null);
		if (clienttasks.isPackageVersion("subscription-manager",">=","1.13.9-1")) {	// post commit a695ef2d1da882c5f851fde90a24f957b70a63ad
			Assert.assertEquals(identityResult.getStderr().trim(), clienttasks.msg_ConsumerNotRegistered,"stderr");	
		} else {
			Assert.assertEquals(identityResult.getStdout().trim(), clienttasks.msg_ConsumerNotRegistered,"stdout");	
		}
	}





	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(	description="verify if Wrong DMI structures Error is filtered from the stderr of subscription-manager command line calls",
			groups={"WrongDMIstructuresError","blockedByBug-706552"},
			enabled=true)
	public void WrongDMIstructuresError() throws Exception {
		String result=	clienttasks.register(sm_clientUsername, sm_clientPassword,
				sm_clientOrg, null, null, null, null, null, null, null,
				(String) null, null, null, null, true, null, null, null, null).getStderr();
		Assert.assertContainsNoMatch(result, "Wrong DMI");
		result=	clienttasks.facts(true, null, null, null, null).getStderr();
		Assert.assertContainsNoMatch(result, "Wrong DMI");
	}

	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(	description="verify if subscription manager cli uses product name comparisons in the list command ",
			groups={"InstalledProductMultipliesAfterSubscription","AcceptanceTests","Tier1Tests","blockedByBug-709412"},
			enabled=true)
	public void InstalledProductMultipliesAfterSubscription() throws Exception {
		if(!sm_serverType.equals(CandlepinType.hosted)) throw new SkipException("To be run against Stage only");
		// jsefler - moving product certs around is too much work, changing to a different configured rhsm.productCertDir...
		//		client.runCommandAndWait("mkdir /root/generatedCertsFolder");
		/* jsefler - test with the script's input automation properties, not hardcoded credentials 
		String serverurl="subscription.rhn.stage.redhat.com:443/subscription";
		String clientUsername="stage_test_12";
		clienttasks.register(clientUsername, sm_rhuiPassword,null, null, null, null, null, null, null, null,
				(String) null, serverurl, null, null, true, null, null, null, null).getStdout();
		 */
		String serverUrl= getServerUrl(clienttasks.getConfFileParameter(clienttasks.rhsmConfFile,"hostname"), clienttasks.getConfFileParameter(clienttasks.rhsmConfFile,"port"), clienttasks.getConfFileParameter(clienttasks.rhsmConfFile,"prefix"));
		clienttasks.register(sm_clientUsername, sm_clientPassword,null, null, null, null, null, null, null, null,(String) null, serverUrl, null, null, true, null, null, null, null);
		//clienttasks.config(null,null,true,new String[]{"rhsm","productcertdir","/usr/share/rhsm/product/RHEL-"+clienttasks.redhatReleaseX});
		//		moveProductCertFiles("*");
		//		client.runCommandAndWait("cp " + "/usr/share/rhsm/product/RHEL-*/Server*.pem" + " "
		//				+ "/root/generatedCertsFolder");
		//		client.runCommandAndWait("mv" + "/root/generatedCertsFolder" + " "+ clienttasks.productCertDir);
		productCertDir = clienttasks.productCertDir;
		clienttasks.updateConfFileParameter(clienttasks.rhsmConfFile, "productCertDir", "/usr/share/rhsm/product/RHEL-"+clienttasks.redhatReleaseX);
		List<InstalledProduct> InstalledProducts=clienttasks.getCurrentlyInstalledProducts();

		List<SubscriptionPool> AvailablePools=clienttasks.getCurrentlyAvailableSubscriptionPools();
		for(SubscriptionPool pools:AvailablePools){
			clienttasks.subscribe(null, null,pools.poolId, null, null, null, null, null, null, null, null, null);
			List<InstalledProduct> InstalledProductsAfterSubscribing=clienttasks.getCurrentlyInstalledProducts();
			Assert.assertEquals(InstalledProducts.size(), InstalledProductsAfterSubscribing.size());
			//				clienttasks.unsubscribeFromTheCurrentlyConsumedProductSubscriptionsCollectively();
			clienttasks.unsubscribe(true, (BigInteger)null, null, null, null);
		}
	}
	@AfterGroups(groups = { "setup" }, value = {"InstalledProductMultipliesAfterSubscription"})
	public void afterInstalledProductMultipliesAfterSubscription() throws IOException {
		if (productCertDir!=null) clienttasks.updateConfFileParameter(clienttasks.rhsmConfFile, "productCertDir", productCertDir);
	}
	String productCertDir=null;


	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(	description="verify Stacking of a future subscription and present subsciption make the product compliant ",
			groups={"StackingFutureSubscriptionWithCurrentSubscription","blockedByBug-966069"},
			enabled=true)
	public void StackingFutureSubscriptionWithCurrentSubscription() throws Exception {

		clienttasks.register(sm_clientUsername, sm_clientPassword,
				sm_clientOrg, null, null, null, null, null, null, null,
				(String) null, null, null, null, true, null, null, null, null);
		clienttasks.autoheal(null, null, true, null, null, null);
		int sockets = 4;
		Map<String, String> factsMap = new HashMap<String, String>();
		factsMap.put("virt.is_guest", Boolean.FALSE.toString());	// stacking subscriptions based on sockets is only applicable on Physical systems now that vcpu compliance has been added to candlepin
		factsMap.put("cpu.cpu_socket(s)", String.valueOf(sockets));
		clienttasks.createFactsFileWithOverridingValues(factsMap);
		clienttasks.facts(null, true, null, null, null);
		Calendar startCalendar = new GregorianCalendar();
		DateFormat yyyy_MM_dd_DateFormat = new SimpleDateFormat("yyyy-MM-dd");

		// attach future stackable subscriptions for "awesomeos-x86_64"
		InstalledProduct awesomeos_x86_64_bits = InstalledProduct.findFirstInstanceWithMatchingFieldFromList("productId", "100000000000002", clienttasks.getCurrentlyInstalledProducts());
		String consumerId = clienttasks.getCurrentConsumerId();
		ownerKey = CandlepinTasks.getOwnerKeyOfConsumerId(sm_serverAdminUsername, sm_serverAdminPassword, sm_serverUrl, consumerId);
		String name,productId;
		List<String> providedProductIds = new ArrayList<String>();
		name = "Test product to check pool refresh";
		productId = "test-product";
		Map<String,String> attributes = new HashMap<String,String>();
		attributes.clear();
		attributes.put("version", "1.0");
		attributes.put("variant", "server");
		attributes.put("arch", "ALL");
		attributes.put("warning_period", "30");
		attributes.put("type", "MKT");
		attributes.put("type", "SVC");
		providedProductIds.add("100000000000002");
		Integer contractNumber = getRandInt();
		Integer accountNumber = getRandInt();
		Calendar endCalendar = new GregorianCalendar();
		endCalendar.set(Calendar.HOUR_OF_DAY, 0);endCalendar.set(Calendar.MINUTE, 0);endCalendar.set(Calendar.SECOND, 0);	// avoid times in the middle of the day
		endCalendar.add(Calendar.YEAR, 2);		// 15 days from today
		Date endDate = endCalendar.getTime();
		startCalendar.set(Calendar.HOUR_OF_DAY, 0);startCalendar.set(Calendar.MINUTE, 0);startCalendar.set(Calendar.SECOND, 0);	// avoid times in the middle of the day
		startCalendar.add(Calendar.YEAR, 1);
		startCalendar.add(Calendar.DATE, 1);
		String onDateToTest = yyyy_MM_dd_DateFormat.format(startCalendar.getTime());
		Date startDate = startCalendar.getTime();		
		CandlepinTasks.deleteSubscriptionsAndRefreshPoolsUsingRESTfulAPI(sm_serverAdminUsername, sm_serverAdminPassword, sm_serverUrl, sm_clientOrg, productId);
		String resourcePath = "/products/"+productId;
		if (SubscriptionManagerTasks.isVersion(servertasks.statusVersion, ">=", "2.0.0")) resourcePath = "/owners/"+sm_clientOrg+resourcePath;
		CandlepinTasks.deleteResourceUsingRESTfulAPI(sm_serverAdminUsername, sm_serverAdminPassword, sm_serverUrl, resourcePath);
		CandlepinTasks.createProductUsingRESTfulAPI(sm_serverAdminUsername, sm_serverAdminPassword, sm_serverUrl, sm_clientOrg, name, productId, 1, attributes, null);
		String requestBody = CandlepinTasks.createSubscriptionRequestBody(20, startDate, endDate, productId, contractNumber, accountNumber, providedProductIds,null).toString();
		new JSONObject(CandlepinTasks.postResourceUsingRESTfulAPI(sm_serverAdminUsername,sm_serverAdminPassword,sm_serverUrl,"/owners/" + ownerKey + "/subscriptions",requestBody));
		JSONObject jobDetail = CandlepinTasks.refreshPoolsUsingRESTfulAPI(sm_serverAdminUsername,sm_serverAdminPassword,sm_serverUrl,ownerKey);
		jobDetail = CandlepinTasks.waitForJobDetailStateUsingRESTfulAPI(sm_serverAdminUsername,sm_serverAdminPassword,sm_serverUrl,jobDetail,"FINISHED", 5*1000, 1);
		for(SubscriptionPool availOnDate :getAvailableFutureSubscriptionsOndate(onDateToTest)){
			if(availOnDate.productId.equalsIgnoreCase("test-product")){
				clienttasks.subscribe(null, null, availOnDate.poolId, null, null,null, null, null, null, null, null, null);
			}
		}

		List<ProductSubscription> futureConsumedProductSubscriptions = clienttasks.getCurrentlyConsumedProductSubscriptions();
		Assert.assertTrue(!futureConsumedProductSubscriptions.isEmpty(), "Future subscriptions for awesomeos-x86_64 have been attached.");

		// attach one currently available stackable subscription for "awesomeos-x86_64"
		SubscriptionPool awesomeos_x86_64_pool = SubscriptionPool.findFirstInstanceWithCaseInsensitiveMatchingFieldFromList("productId","awesomeos-x86_64", clienttasks.getCurrentlyAvailableSubscriptionPools());
		Assert.assertNotNull(awesomeos_x86_64_pool, "Found a currently available pool for awesomeos-x86_64.");
		clienttasks.subscribe(null, null, awesomeos_x86_64_pool.poolId, null, null,"2", null, null, null, null, null, null);

		/*InstalledProduct*/ awesomeos_x86_64_bits = InstalledProduct.findFirstInstanceWithMatchingFieldFromList("productId", "100000000000002", clienttasks.getCurrentlyInstalledProducts());
		Assert.assertNotNull(awesomeos_x86_64_bits, "Found a currently installed product for awesomeos-x86_64 bits.");
		Assert.assertEquals(awesomeos_x86_64_bits.status, "Partially Subscribed", "The future stackable subscriptions for awesomeos-x86_64 that are attached should NOT stack with the currently active subscription.  The result should be a partially covered installed product.");
	}


	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(	description="verify the system compliance after deleting the consumer",
			groups={"ComplianceAfterConsumerDeletion"},
			enabled=true)
	public void ComplianceAfterConsumerDeletion() throws Exception {
		clienttasks.register(sm_clientUsername, sm_clientPassword,
				sm_clientOrg, null, null, null, null, null, null, null,
				(String) null, null, null, null, true, null, null, null, null);
		String consumerid=clienttasks.getCurrentConsumerId();
		CandlepinTasks.deleteResourceUsingRESTfulAPI(sm_serverAdminUsername, sm_serverAdminPassword, sm_serverUrl, "/consumers/"+consumerid);
		String complianceStatus = CandlepinTasks.getConsumerCompliance(sm_serverAdminUsername, sm_serverAdminPassword, SubscriptionManagerBaseTestScript.sm_serverUrl, consumerid).getString("displayMessage");

		String message="Consumer "+consumerid+" has been deleted";
		if (!clienttasks.workaroundForBug876764(sm_serverType)) message = "Unit "+consumerid+" has been deleted";

		Assert.assertContainsMatch(message, complianceStatus);

	}


	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(	description="verify if ipV4_Address is unknown in the facts list",
			groups={"VerifyIfIPV4_AddressIsUnknown","blockedByBug-694662"},
			enabled=true)
	public void VerifyIfIPV4_AddressIsUnknown() throws Exception {
		clienttasks.register(sm_clientUsername, sm_clientPassword,
				sm_clientOrg, null, null, null, null, null, null, null,
				(String) null, null, null, null, true, null, null, null, null);
		Map<String, String> ipv4=clienttasks.getFacts("ipv4_address");
		Assert.assertFalse(ipv4.containsValue("unknown"));
	}




	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(	description="verify if configurations like manage_repos have default values",
			groups={"defaultValueForManageRepos","blockedByBug-807721"},
			enabled=true)
	public void defaultValueForManageReposConfiguration() throws Exception {

		String result =clienttasks.config(true, null, null, (String[])null).getStdout();
		clienttasks.commentConfFileParameter(clienttasks.rhsmConfFile, "manage_repos");
		String resultAfterCommentingtheParameter=clienttasks.config(true, null, null, (String[])null).getStdout();
		Assert.assertEquals(result, resultAfterCommentingtheParameter);
		clienttasks.uncommentConfFileParameter(clienttasks.rhsmConfFile, "manage_repos");

	}

	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(	description="verify if refresh pools will not notice change in provided products",
			groups={"RefreshPoolAfterChangeInProvidedProducts","blockedByBug-665118"},
			enabled=false)
	public void RefreshPoolAfterChangeInProvidedProducts() throws Exception {
		clienttasks.register(sm_clientUsername, sm_clientPassword,
				sm_clientOrg, null, null, null, null, null, null, null,
				(String) null, null, null, null, true, null, null, null, null);

		String consumerId = clienttasks.getCurrentConsumerId();
		ownerKey = CandlepinTasks.getOwnerKeyOfConsumerId(sm_serverAdminUsername, sm_serverAdminPassword, sm_serverUrl, consumerId);
		String name,productId;
		List<String> providedProductIds = new ArrayList<String>();
		name = "Test product to check pool refresh";
		productId = "test-product";
		Map<String,String> attributes = new HashMap<String,String>();
		attributes.clear();
		attributes.put("version", "1.0");
		attributes.put("variant", "server");
		attributes.put("arch", "ALL");
		attributes.put("warning_period", "30");
		attributes.put("type", "MKT");
		attributes.put("type", "SVC");
		providedProductIds.add("37060");
		Integer contractNumber = getRandInt();
		Integer accountNumber = getRandInt();
		Calendar endCalendar = new GregorianCalendar();
		endCalendar.set(Calendar.HOUR_OF_DAY, 0);endCalendar.set(Calendar.MINUTE, 0);endCalendar.set(Calendar.SECOND, 0);	// avoid times in the middle of the day
		endCalendar.add(Calendar.MINUTE, 15*24*60);		// 15 days from today
		Date endDate = endCalendar.getTime();
		Calendar startCalendar = new GregorianCalendar();
		startCalendar.set(Calendar.HOUR_OF_DAY, 0);startCalendar.set(Calendar.MINUTE, 0);startCalendar.set(Calendar.SECOND, 0);	// avoid times in the middle of the day
		startCalendar.add(Calendar.MINUTE, -1*24*60);	// 1 day ago

		Date startDate = startCalendar.getTime();		
		CandlepinTasks.deleteSubscriptionsAndRefreshPoolsUsingRESTfulAPI(sm_serverAdminUsername, sm_serverAdminPassword, sm_serverUrl, sm_clientOrg, productId);
		String resourcePath = "/products/"+productId;
		if (SubscriptionManagerTasks.isVersion(servertasks.statusVersion, ">=", "2.0.0")) resourcePath = "/owners/"+sm_clientOrg+resourcePath;
		CandlepinTasks.deleteResourceUsingRESTfulAPI(sm_serverAdminUsername, sm_serverAdminPassword, sm_serverUrl, resourcePath);

		CandlepinTasks.createProductUsingRESTfulAPI(sm_serverAdminUsername, sm_serverAdminPassword, sm_serverUrl, sm_clientOrg, name, productId, 1, attributes, null);
		String requestBody = CandlepinTasks.createSubscriptionRequestBody(20, startDate, endDate, productId, contractNumber, accountNumber, providedProductIds,null).toString();
		JSONObject jsonSubscription = new JSONObject(CandlepinTasks.postResourceUsingRESTfulAPI(sm_serverAdminUsername,sm_serverAdminPassword,sm_serverUrl,"/owners/" + ownerKey + "/subscriptions",requestBody));
		JSONObject jobDetail = CandlepinTasks.refreshPoolsUsingRESTfulAPI(sm_serverAdminUsername,sm_serverAdminPassword,sm_serverUrl,ownerKey);
		jobDetail = CandlepinTasks.waitForJobDetailStateUsingRESTfulAPI(sm_serverAdminUsername,sm_serverAdminPassword,sm_serverUrl,jobDetail,"FINISHED", 5*1000, 1);
		String poolId=null;
		for(SubscriptionPool pools:clienttasks.getCurrentlyAvailableSubscriptionPools()){
			if(pools.productId.equals(productId)){
				poolId=pools.poolId;
			}
		}
		System.out.println("string is ........  "+jsonSubscription);
		providedProductIds.remove("37060");
		providedProductIds.add("100000000000002");

		List<JSONObject> pprods = new ArrayList<JSONObject>();
		if (providedProducts!=null) for (String id: providedProductIds) {
			JSONObject jo = new JSONObject();
			jo.put("id", id);
			pprods.add(jo);
		}
		jsonSubscription.remove("derivedProvidedProducts");
		jsonSubscription.put("providedProducts", pprods);
		System.out.println(jsonSubscription);

		//	String sub="{\"quantity\":\"8\"}]}";
		//	JSONObject jsonData= new JSONObject(jsonSubscription);
		CandlepinTasks.putResourceUsingRESTfulAPI(sm_serverAdminUsername,sm_serverAdminPassword, sm_serverUrl,"/owners/admin/subscriptions/", jsonSubscription);
		jobDetail = CandlepinTasks.refreshPoolsUsingRESTfulAPI(sm_serverAdminUsername, sm_serverAdminPassword, sm_serverUrl, ownerKey);
		CandlepinTasks.waitForJobDetailStateUsingRESTfulAPI(sm_serverAdminUsername,sm_serverAdminPassword,sm_serverUrl,jobDetail,"FINISHED", 5*1000, 1);
		for(SubscriptionPool pools:clienttasks.getCurrentlyAvailableSubscriptionPools()){
			if(pools.productId.equals(productId)){
				poolId=pools.poolId;
			}
		}
	}

	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(	description="verify Remote Server Exception is getting displayed for Server 500 Error",
			groups={"DisplayOfRemoteServerExceptionForServer500Error","blockedByBug-668814"},
			enabled=true)
	public void DisplayOfRemoteServerExceptionForServer500Error() throws Exception {
		String prefixValueBeforeExecution=clienttasks.getConfFileParameter(clienttasks.rhsmConfFile, "prefix");
		List<String[]> listOfSectionNameValues = new ArrayList<String[]>();
		listOfSectionNameValues.add(new String[] { "server","hostname".toLowerCase(),configuredHostname});
		listOfSectionNameValues.add(new String[] { "server","port".toLowerCase(), "8443" });
		listOfSectionNameValues.add(new String[] { "server","prefix", "/footestprefix" });
		clienttasks.config(null, null, true, listOfSectionNameValues);
		SSHCommandResult registerResult=clienttasks.register_(sm_clientUsername, sm_clientPassword,
				sm_clientOrg, null, null, null, null, null, null, null,
				(String) null, null, null, null, true, null, null, null, null);
		String Expected_Message=clienttasks.msg_NetworkErrorCheckConnection;
		// [jsefler 10/30/2014] This test is currently encountering a 404 instead of a 500;  TODO change this testcase to force a 500 error
		// Error during registration: Server error attempting a GET to /footestprefix/ returned status 404
		Expected_Message = clienttasks.msg_RemoteErrorCheckConnection;
		listOfSectionNameValues.add(new String[] { "server","prefix", prefixValueBeforeExecution.trim() });
		clienttasks.config(null, null, true, listOfSectionNameValues);
		if (clienttasks.isPackageVersion("subscription-manager",">=","1.13.9-1")) {	// post commit a695ef2d1da882c5f851fde90a24f957b70a63ad
			Assert.assertEquals(registerResult.getStderr().trim(), Expected_Message,"stderr");	
		} else {
			Assert.assertEquals(registerResult.getStdout().trim(), Expected_Message,"stdout");	
		}
	}

	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(	description="verify Manual Changes To Redhat.Repo is sticky",
			groups={"ManualChangesToRedhat_Repo","blockedByBug-797243"},
			enabled=true)
	public void ManualChangesToRedhat_Repo() throws Exception {
		List<String[]> listOfSectionNameValues = new ArrayList<String[]>();
		listOfSectionNameValues.add(new String[] { "rhsmcertd",
				"autoAttachInterval".toLowerCase(), "1440" });
		clienttasks.config(null, null, true, listOfSectionNameValues);
		clienttasks.register(sm_clientUsername, sm_clientPassword,
				sm_clientOrg, null, null, null, null, null, null, null,
				(String) null, null, null, null, true, false, null, null, null);
		List<SubscriptionPool> Availablepools=clienttasks.getCurrentlyAvailableSubscriptionPools();
		SubscriptionPool pool =Availablepools.get(randomGenerator.nextInt(Availablepools.size()));
		clienttasks.subscribeToSubscriptionPool(pool);
		for(Repo repo : clienttasks.getCurrentlySubscribedRepos()){
			if(repo.repoId.equals("always-enabled-content")){

				Assert.assertTrue(repo.enabled);
			}
		}		client.runCommandAndWait("sed -i \"/\\[always-enabled-content]/,/\\[/s/^enabled\\s*=.*/Enabled: false/\" /etc/yum.repos.d/redhat.repo");	
		for(Repo repo : clienttasks.getCurrentlySubscribedRepos()){
			if(repo.repoId.equals("always-enabled-content")){
				Assert.assertFalse(repo.enabled);
			}
		}
		client.runCommandAndWait(" yum repolist enabled");
		clienttasks.unsubscribeFromAllOfTheCurrentlyConsumedProductSubscriptions();
		String expected_message="This system has no repositories available through subscriptions.";
		String reposlist=clienttasks.repos(true, null, null, (String)null, null, null, null, null).getStdout();
		Assert.assertEquals(reposlist.trim(), expected_message);
		clienttasks.unregister(null, null, null);
		clienttasks.register(sm_clientUsername, sm_clientPassword,
				sm_clientOrg, null, null, null, null, null, null, null,
				(String) null, null, null, null, true, null, null, null, null);
		reposlist=clienttasks.repos(true, null, null, (String)null, null, null, null, null).getStdout();
		Assert.assertEquals(reposlist.trim(), expected_message);
		clienttasks.subscribe(true, null,(String)null, null, null, null, null, null, null, null, null, null);
		for(Repo repo : clienttasks.getCurrentlySubscribedRepos()){
			if(repo.repoId.equals("always-enabled-content")){
				Assert.assertTrue(repo.enabled);
			}
		}
	}




	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(	description="Extraneous / InRequest urls in the rhsm.log file",
			groups={"ExtraneousSlashInRequesturls","blockedByBug-848836"},
			enabled=true)
	public void ExtraneousSlashInRequesturls () throws Exception {
		clienttasks.register(sm_clientUsername, sm_clientPassword,
				sm_clientOrg, null, null, null, null, null, null, null,
				(String) null, null, null, null, true, null, null, null, null);
		Boolean actual =false;
		String LogMarker = System.currentTimeMillis()+" Testing ***************************************************************";
		List<String[]> listOfSectionNameValues = new ArrayList<String[]>();
		listOfSectionNameValues.add(new String[] { "server",
				"prefix".toLowerCase(), "/candlepin" });
		clienttasks.config(null, null, true,listOfSectionNameValues);
		Boolean flag = RegexInRhsmLog("//",RemoteFileTasks.getTailFromMarkedFile(client, clienttasks.rhsmLogFile, LogMarker,"GET"));
		Assert.assertEquals(flag, actual);

	}




	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(	description="verify the first system is unregistered when the second system is registered using consumerid of the first",
			groups={"SubscriptionManagerAccess","blockedByBug-878588"},
			enabled=false)
	public void SubscriptionManagerAccess() throws Exception {
		/*String username="testlogin";
		//String passwords="123testpassword";
		client.runCommandAndWait("useradd "+username);
		//client.runCommandAndWait("echo "+passwords+" | passwd "+username + " --stdin");
	//	String result=client.runCommandAndWait("su - "+username+" -c subscription-manager").getStderr();

	//	client.runCommandAndWait("su - "+username);
	//	System.out.println(result + "is the result");
		String expectedMessage="Error: this command requires root access to execute";
	//	Assert.assertEquals(client.getStderr().trim(), expectedMessage);
		//SSHCommandResult result=client.runCommandAndWait("su "+username);
		//result=client.runCommandAndWait(clienttasks.command);
		//client.runCommandAndWait("logout");
	//	client=new SSHCommandRunner(sm_clientHostname, sm_sshUser, sm_sshKeyPrivate,sm_sshkeyPassphrase,null);
		client.runCommandAndWait("userdel -r "+username);*/


	}

	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(	description="verify the first system is unregistered when the second system is registered using consumerid of the first",
			groups={"RegisterTwoClientsUsingSameConsumerId","blockedByBug-949990"},
			enabled=false)
	public void RegisterTwoClientsUsingSameConsumerId() throws Exception {
		clienttasks.register(sm_clientUsername, sm_clientPassword,
				sm_clientOrg, null, null, null, null, null, null, null,
				(String) null, null, null, null, true, null, null, null, null);
		String consumerid=clienttasks.getCurrentConsumerId();
		clienttasks.subscribe(true, null,(String)null, null, null,  null, null, null, null, null, null, null);
		List<ProductSubscription> consumedSubscriptionOnFirstMachine=clienttasks.getCurrentlyConsumedProductSubscriptions();
		client2tasks.register(sm_clientUsername, sm_clientPassword, null, null, null, null, consumerid, null, null, null,(String)null, null, null, null, true, null, null, null, null);
		String result=clienttasks.identity(null, null, null, null, null, null, null).getStdout();
		List<ProductSubscription> consumedSubscriptionOnSecondMachine=clienttasks.getCurrentlyConsumedProductSubscriptions();
		Assert.assertEquals(consumedSubscriptionOnFirstMachine, consumedSubscriptionOnSecondMachine);
		result=clienttasks.getCurrentConsumerId();
		Assert.assertEquals(result.trim(),consumerid);
		Assert.assertEquals(result.trim(), clienttasks.msg_ConsumerNotRegistered);

	}

	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(	description="verify proxy option in repos list ",
			groups={"ProxyOptionForRepos"},
			enabled=true)
	public void ProxyOptionForRepos() throws Exception {
		clienttasks.register(sm_clientUsername, sm_clientPassword,
				sm_clientOrg, null, null, null, null, null, null, null,
				(String) null, null, null, null, true, null, null, null, null);
		clienttasks.subscribe(true, null,(String)null, null, null, null, null, null, null, null, null, null);
		String result=clienttasks.repos_(true,null,null, (String)null, (String)null, sm_basicauthproxyHostname+":"+sm_basicauthproxyPort, null, null).getStdout();
		String expectedMessage="Network error, unable to connect to server."+"\n"+"Please see /var/log/rhsm/rhsm.log for more information.";
		Assert.assertNotSame(result.trim(), expectedMessage);
	}

	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(	description="verify Future subscription added to the activation key ",
			groups={"AddingFutureSubscriptionToActivationKey"},
			enabled=true)
	public void AddingFutureSubscriptionToActivationKey() throws Exception {
		Integer addQuantity=1;
		clienttasks.register(sm_clientUsername, sm_clientPassword,
				sm_clientOrg, null, null, null, null, null, null, null,
				(String) null, null, null, null, true, null, null, null, null);
		clienttasks.autoheal(null, null, true, null, null, null);
		Calendar now = new GregorianCalendar();
		DateFormat yyyy_MM_dd_DateFormat = new SimpleDateFormat("yyyy-MM-dd");
		now.add(Calendar.YEAR, 1);
		now.add(Calendar.DATE, 1);
		String onDateToTest = yyyy_MM_dd_DateFormat.format(now.getTime());
		List<SubscriptionPool> availOnDate = getAvailableFutureSubscriptionsOndate(onDateToTest);
		if(availOnDate.size()==0) throw new SkipException("Sufficient future pools are not available");
		int i=-1;
		availOnDate = getRandomSubsetOfList(availOnDate, availOnDate.size());	// randomize the order of the future available SubscriptionPools
		for (int j=0; j<availOnDate.size(); j++) {
			if (!availOnDate.get(j).provides.isEmpty()) {
				i=j;
				break;
			}
		}
		Assert.assertTrue(i!=-1, "Found a future available pool that provides products.");

		List<String> providedProductIds = CandlepinTasks.getPoolProvidedProductIds(sm_clientUsername,sm_clientPassword, sm_serverUrl, availOnDate.get(i).poolId);
		String name = String.format("%s_%s-ActivationKey%s", sm_clientUsername,
				sm_clientOrg, System.currentTimeMillis());
		Map<String, String> mapActivationKeyRequest = new HashMap<String, String>();
		mapActivationKeyRequest.put("name", name);
		JSONObject jsonActivationKeyRequest = new JSONObject(
				mapActivationKeyRequest);
		JSONObject jsonActivationKey = new JSONObject(
				CandlepinTasks.postResourceUsingRESTfulAPI(sm_clientUsername,
						sm_clientPassword, sm_serverUrl, "/owners/"
								+ sm_clientOrg + "/activation_keys",
								jsonActivationKeyRequest.toString()));

		new JSONObject(CandlepinTasks.postResourceUsingRESTfulAPI(sm_clientUsername, sm_clientPassword, sm_serverUrl, "/activation_keys/" + jsonActivationKey.getString("id") + "/pools/" +availOnDate.get(i).poolId+(addQuantity==null?"":"?quantity="+addQuantity), null));
		clienttasks.unregister(null, null, null);
		clienttasks.register(null, null, sm_clientOrg, null, null, null, null, null, null, null, name, null, null, null, true, null, null, null, null);			
		clienttasks.autoheal(null, null, true, null, null, null);
		clienttasks.listConsumedProductSubscriptions();
		for(InstalledProduct result:clienttasks.getCurrentlyInstalledProducts()){
			if (providedProductIds.contains(result.productId)) {
				Assert.assertEquals(result.status, "Future Subscription");
			}
		}
	}


	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(	description="create virt-only pool and check if lists on available list of physical and virtual machine",
			groups={"createVirtOnlyPool"},
			enabled=true)
	public void createVirtOnlyPool () throws Exception {
		clienttasks.register(sm_clientUsername, sm_clientPassword,
				sm_clientOrg, null, null, null, null, null, null, null,
				(String) null, null, null, null, true, null, null, null, null);

		String consumerId = clienttasks.getCurrentConsumerId();
		ownerKey = CandlepinTasks.getOwnerKeyOfConsumerId(sm_serverAdminUsername, sm_serverAdminPassword, sm_serverUrl, consumerId);
		String name,productId;
		List<String> providedProductIds = new ArrayList<String>();
		name = "Test product to check virt_only pool";
		productId = "test-product";
		Map<String,String> attributes = new HashMap<String,String>();
		attributes.clear();
		attributes.put("version", "1.0");
		attributes.put("variant", "server");
		attributes.put("arch", "ALL");
		attributes.put("warning_period", "30");
		attributes.put("type", "MKT");
		attributes.put("type", "SVC");
		attributes.put("virt_only", "true");
		providedProductIds.add("37060");
		Integer contractNumber = getRandInt();
		Integer accountNumber = getRandInt();
		Calendar endCalendar = new GregorianCalendar();
		endCalendar.set(Calendar.HOUR_OF_DAY, 0);endCalendar.set(Calendar.MINUTE, 0);endCalendar.set(Calendar.SECOND, 0);	// avoid times in the middle of the day
		endCalendar.add(Calendar.MINUTE, 15*24*60);		// 15 days from today
		Date endDate = endCalendar.getTime();
		Calendar startCalendar = new GregorianCalendar();
		startCalendar.set(Calendar.HOUR_OF_DAY, 0);startCalendar.set(Calendar.MINUTE, 0);startCalendar.set(Calendar.SECOND, 0);	// avoid times in the middle of the day
		startCalendar.add(Calendar.MINUTE, -1*24*60);	// 1 day ago

		Date startDate = startCalendar.getTime();		
		CandlepinTasks.deleteSubscriptionsAndRefreshPoolsUsingRESTfulAPI(sm_serverAdminUsername, sm_serverAdminPassword, sm_serverUrl, sm_clientOrg, productId);
		String resourcePath = "/products/"+productId;
		if (SubscriptionManagerTasks.isVersion(servertasks.statusVersion, ">=", "2.0.0")) resourcePath = "/owners/"+sm_clientOrg+resourcePath;
		CandlepinTasks.deleteResourceUsingRESTfulAPI(sm_serverAdminUsername, sm_serverAdminPassword, sm_serverUrl, resourcePath);

		CandlepinTasks.createProductUsingRESTfulAPI(sm_serverAdminUsername, sm_serverAdminPassword, sm_serverUrl, sm_clientOrg, name, productId, 1, attributes, null);
		String requestBody = CandlepinTasks.createSubscriptionRequestBody(20, startDate, endDate, productId, contractNumber, accountNumber, providedProductIds,null).toString();
		new JSONObject(CandlepinTasks.postResourceUsingRESTfulAPI(sm_serverAdminUsername,sm_serverAdminPassword,sm_serverUrl,"/owners/" + ownerKey + "/subscriptions",requestBody));
		JSONObject jobDetail = CandlepinTasks.refreshPoolsUsingRESTfulAPI(sm_serverAdminUsername,sm_serverAdminPassword,sm_serverUrl,ownerKey);
		jobDetail = CandlepinTasks.waitForJobDetailStateUsingRESTfulAPI(sm_serverAdminUsername,sm_serverAdminPassword,sm_serverUrl,jobDetail,"FINISHED", 5*1000, 1);
		String poolid=CandlepinTasks.getPoolIdFromProductNameAndContractNumber(sm_serverAdminUsername,sm_serverAdminPassword,sm_serverUrl, "admin", name, contractNumber.toString());
		String isGuest=clienttasks.getFactValue("virt.is_guest");
		if(isGuest.equalsIgnoreCase("true")){

			Assert.assertContainsMatch(clienttasks.getCurrentlyAllAvailableSubscriptionPools().toString(), poolid);
		}else if(isGuest.equalsIgnoreCase("False")){
			Assert.assertContainsNoMatch(clienttasks.getCurrentlyAllAvailableSubscriptionPools().toString(), poolid);
		}
		
		// Note: After attaching this subscription in the subscription-manager-gui, the date range is yellow and an exclamation point icon is displayed in the "My Subscriptions" tab to show the attached subscription is about to expire. 
	}

	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(	description="verify Facts for change in OS ",
			groups={"FactsForChangeIn_OS"},
			enabled=true)
	@ImplementsNitrateTest(caseId=56387)
	public void FactsForChangeIn_OS () throws Exception {
		String originalhostname=clienttasks.hostname;
		String changedHostname="redhat";
		String result=clienttasks.getFactValue("network.hostname");
		Assert.assertEquals(result, originalhostname," Fact matches the hostname");
		client.runCommandAndWait("hostname "+changedHostname);
		result=clienttasks.getFactValue("network.hostname");
		Assert.assertEquals(result, changedHostname," Fact matches the hostname(After changing the hostname..)");

	}
	@AfterGroups(groups={"setup"},value="FactsForChangeIn_OS")
	public void restoreHostnameAfterFactsForChangeIn_OS() {
		if (clienttasks!=null) client.runCommandAndWait("hostname "+clienttasks.hostname);
	}


	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(	description="Consumer unsubscribed when Subscription revoked",
			groups={"CRLTest"},
			enabled=true)
	@ImplementsNitrateTest(caseId=55355)
	public void CRLTest() {
		clienttasks.register(sm_clientUsername, sm_clientPassword,
				sm_clientOrg ,null, null, null, null, null, null, null,
				(String) null, null, null, null, true, false, null, null, null);
		List<SubscriptionPool> availPools = clienttasks.getCurrentlyAvailableSubscriptionPools(); 
		File entitlementCertFile=clienttasks.subscribeToSubscriptionPool(availPools.get(randomGenerator.nextInt(availPools.size())),sm_serverAdminUsername,sm_serverAdminPassword,sm_serverUrl);

		BigInteger serialNumber = clienttasks.getSerialNumberFromEntitlementCertFile(entitlementCertFile);

		clienttasks.unsubscribe(null,serialNumber, null, null, null);
		sleep(2/*min*/*60*1000); // give the server time to update; schedule is set in /etc/candlepin/candlepin.conf pinsetter.org.candlepin.pinsetter.tasks.CertificateRevocationListTask.schedule=0 0/2 * * * ?
		RevokedCert revokedCert = RevokedCert.findFirstInstanceWithMatchingFieldFromList("serialNumber", serialNumber, servertasks.getCurrentlyRevokedCerts());
		Assert.assertNotNull(revokedCert, "Found expected Revoked Cert on the server's Certificate Revocation List (CRL) after unsubscribing from serial '"+serialNumber+"'.");
		log.info("Verified revoked certificate: "+revokedCert);
	}


	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(	description="Consumer unsubscribed when Subscription revoked",
			groups={"ConsumerUnsubscribedWhenSubscriptionRevoked","blockedByBug-947429"},
			enabled=true)
	@ImplementsNitrateTest(caseId=56025)
	public void ConsumerUnsubscribedWhenSubscriptionRevoked() throws Exception {
		clienttasks.register(sm_clientUsername, sm_clientPassword,
				sm_clientOrg, null, null, null, null, null, null, null,
				(String) null, null, null, null, true, null, null, null, null);
		clienttasks.autoheal(null, null, true, null, null, null);
		String consumerId = clienttasks.getCurrentConsumerId();
		ownerKey = CandlepinTasks.getOwnerKeyOfConsumerId(sm_serverAdminUsername, sm_serverAdminPassword, sm_serverUrl, consumerId);
		String name,productId;
		List<String> providedProductIds = new ArrayList<String>();
		name = "Test product to check subscription-removal";
		productId = "test-product";
		Map<String,String> attributes = new HashMap<String,String>();
		attributes.clear();
		attributes.put("version", "1.0");
		attributes.put("variant", "server");
		attributes.put("arch", "ALL");
		attributes.put("warning_period", "30");
		attributes.put("type", "MKT");
		attributes.put("type", "SVC");
		File entitlementCertFile=null;
		CandlepinTasks.deleteSubscriptionsAndRefreshPoolsUsingRESTfulAPI(sm_serverAdminUsername, sm_serverAdminPassword, sm_serverUrl, sm_clientOrg, productId);
		String resourcePath = "/products/"+productId;
		if (SubscriptionManagerTasks.isVersion(servertasks.statusVersion, ">=", "2.0.0")) resourcePath = "/owners/"+sm_clientOrg+resourcePath;
		CandlepinTasks.deleteResourceUsingRESTfulAPI(sm_serverAdminUsername, sm_serverAdminPassword, sm_serverUrl, resourcePath);
		clienttasks.unsubscribeFromAllOfTheCurrentlyConsumedProductSubscriptions();
		CandlepinTasks.createProductUsingRESTfulAPI(sm_serverAdminUsername, sm_serverAdminPassword, sm_serverUrl, sm_clientOrg, name+" BITS", productId, 1, attributes, null);
		CandlepinTasks.createSubscriptionAndRefreshPoolsUsingRESTfulAPI(sm_serverAdminUsername, sm_serverAdminPassword, sm_serverUrl, sm_clientOrg, 20, -1*24*60/*1 day ago*/, 15*24*60/*15 days from now*/, getRandInt(), getRandInt(), productId, providedProductIds,null);
		server.runCommandAndWait("rm -rf "+servertasks.candlepinCRLFile);
		for(SubscriptionPool pool:clienttasks.getCurrentlyAllAvailableSubscriptionPools()){
			if(pool.productId.equals(productId)){
				entitlementCertFile=	clienttasks.subscribeToSubscriptionPool(pool,sm_serverAdminUsername,sm_serverAdminPassword,sm_serverUrl);
			}
		}
		Assert.assertNotNull(entitlementCertFile,"Successfully created and subscribed to product subscription '"+productId+"' created by and needed for this test.");
		EntitlementCert entitlementCert = clienttasks.getEntitlementCertFromEntitlementCertFile(entitlementCertFile);
		List <ProductSubscription> consumedSusbscription=clienttasks.getCurrentlyConsumedProductSubscriptions();
		Assert.assertFalse(consumedSusbscription.isEmpty());
		CandlepinTasks.deleteSubscriptionsAndRefreshPoolsUsingRESTfulAPI(sm_serverAdminUsername, sm_serverAdminPassword, sm_serverUrl, sm_clientOrg, productId);
		resourcePath = "/products/"+productId;
		if (SubscriptionManagerTasks.isVersion(servertasks.statusVersion, ">=", "2.0.0")) resourcePath = "/owners/"+sm_clientOrg+resourcePath;
		CandlepinTasks.deleteResourceUsingRESTfulAPI(sm_serverAdminUsername, sm_serverAdminPassword, sm_serverUrl, resourcePath);
		sleep(2/*min*/*60*1000);

		// verify the entitlement serial has been added to the CRL on the server
		List<RevokedCert> revokedCerts=servertasks.getCurrentlyRevokedCerts();
		RevokedCert revokedCert = RevokedCert.findFirstInstanceWithMatchingFieldFromList("serialNumber", entitlementCert.serialNumber, revokedCerts);
		Assert.assertNotNull(revokedCert,"The Certificate Revocation List file on the candlepin server contains an entitlement serial '"+entitlementCert.serialNumber+"' to the product subscription '"+productId+"' that was just deleted on the candlepin server.");

		// trigger the rhsmcertd on the system and verify the entitlement has been removed
		clienttasks.run_rhsmcertd_worker(false);
		Assert.assertTrue(clienttasks.getCurrentlyConsumedProductSubscriptions().isEmpty(), "The revoked entitlement has been removed from the system by rhsmcertd.");
	}

	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(	description = "do not persist --serverurl option values to rhsm.conf when calling subscription-manager modules: orgs, environment, service-level", 
			groups = {"AcceptanceTests","Tier1Tests","blockedByBug-889573"},
			enabled = false)	// FIXME: There is some bad test logic in this testcase and is failing against hosted.  Passing a --serverurl to register module will be persisted to the rhsm.conf, but not the orgs, environments, or service_level modules.
	public void ServerUrloptionValuesInRHSMFile() throws JSONException,Exception {
		if(!sm_serverType.equals(CandlepinType.hosted)) throw new SkipException("To be run against Stage only");
		String clientUsername="stage_test_12";
		String serverurl="subscription.rhn.stage.redhat.com:443/subscription";
		String hostnameBeforeExecution=clienttasks.getConfFileParameter(clienttasks.rhsmConfFile, "hostname");
		String portBeforeExecution=clienttasks.getConfFileParameter(clienttasks.rhsmConfFile, "port");
		String prefixBeforeExecution=clienttasks.getConfFileParameter(clienttasks.rhsmConfFile, "prefix");
		clienttasks.register(clientUsername, sm_rhuiPassword,null, null, null, null, null, null, null, null,
				(String) null, serverurl, null, null, true, null, null, null, null).getStdout();	List<String[]> listOfSectionNameValues = new ArrayList<String[]>();
				listOfSectionNameValues.add(new String[] { "server","hostname".toLowerCase(),hostnameBeforeExecution});
				listOfSectionNameValues.add(new String[] { "server","port".toLowerCase(), "8443" });
				listOfSectionNameValues.add(new String[] { "server","prefix".toLowerCase(), "/candlepin" });
				clienttasks.config(null, null, true, listOfSectionNameValues);
				clienttasks.orgs(clientUsername, sm_rhuiPassword, serverurl, null, null, null, null);
				Assert.assertEquals(clienttasks.getConfFileParameter(clienttasks.rhsmConfFile, "hostname"), hostnameBeforeExecution);
				Assert.assertEquals(clienttasks.getConfFileParameter(clienttasks.rhsmConfFile, "port"),portBeforeExecution);
				Assert.assertEquals(clienttasks.getConfFileParameter(clienttasks.rhsmConfFile, "prefix"),prefixBeforeExecution);

				clienttasks.service_level(true, null, null, null, clientUsername, sm_rhuiPassword, null, serverurl, null, null, null, null);
				Assert.assertEquals(clienttasks.getConfFileParameter(clienttasks.rhsmConfFile, "hostname"), hostnameBeforeExecution);
				Assert.assertEquals(clienttasks.getConfFileParameter(clienttasks.rhsmConfFile, "port"),portBeforeExecution);
				Assert.assertEquals(clienttasks.getConfFileParameter(clienttasks.rhsmConfFile, "prefix"),prefixBeforeExecution);
				clienttasks.environments(clientUsername, sm_rhuiPassword, null, serverurl, null, null, null, null);
				Assert.assertEquals(clienttasks.getConfFileParameter(clienttasks.rhsmConfFile, "hostname"), hostnameBeforeExecution);
				Assert.assertEquals(clienttasks.getConfFileParameter(clienttasks.rhsmConfFile, "port"),portBeforeExecution);
				Assert.assertEquals(clienttasks.getConfFileParameter(clienttasks.rhsmConfFile, "prefix"),prefixBeforeExecution);
	}


	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(description = "verify if CLI lets you set consumer nameto empty string and defaults to sm_clientUsername", 
			groups = { "VerifyConsumerNameTest","blockedByBug-669395"}, enabled = true)
	public void VerifyConsumerNameTest() throws JSONException,Exception {
		String consumerName="tester";
		clienttasks.register(sm_clientUsername, sm_clientPassword,sm_clientOrg, null, null, null, null, null, null, null,
				(String) null, null, null, null, true, null, null, null, null);
		SSHCommandResult result=clienttasks.identity(null, null, null, null, null, null, null);
		Assert.assertContainsMatch(result.getStdout(), "name: "+clienttasks.hostname);
		clienttasks.register(sm_clientUsername, sm_clientPassword,sm_clientOrg, null, null, consumerName, null, null, null, null,
				(String) null, null, null, null, true, null, null, null, null);
		result=clienttasks.identity(null, null, null, null, null, null, null);
		String expected="name: "+consumerName;
		Assert.assertContainsMatch(result.getStdout(), expected);
		String consumerId=clienttasks.getCurrentConsumerId();
		clienttasks.clean(null, null, null);
		consumerName="consumer";
		clienttasks.register(sm_clientUsername, sm_clientPassword,sm_clientOrg, null, null, consumerName, consumerId, null, null, null,
				(String) null, null, null, null, true, null, null, null, null);
		result=clienttasks.identity(null, null, null, null, null, null, null);
		Assert.assertContainsMatch(result.getStdout(), expected);
		clienttasks.clean(null, null, null);
		result=clienttasks.register_(sm_clientUsername, sm_clientPassword,sm_clientOrg, null, null, "", consumerId, null, null, null,
				(String) null, null, null, null, true, null, null, null, null);
		if (clienttasks.isPackageVersion("subscription-manager",">=","1.13.8-1")) {	// post commit df95529a5edd0be456b3528b74344be283c4d258
			Assert.assertEquals(result.getStderr().trim(), "Error: system name can not be empty.","stderr");
		} else {
			Assert.assertEquals(result.getStdout().trim(), "Error: system name can not be empty.","stdout");
		}
		result=clienttasks.register_(sm_clientUsername, sm_clientPassword,sm_clientOrg, null, null, "", null, null, null, null,
				(String) null, null, null, null, true, null, null, null, null);
		if (clienttasks.isPackageVersion("subscription-manager",">=","1.13.8-1")) {	// post commit df95529a5edd0be456b3528b74344be283c4d258
			Assert.assertEquals(result.getStderr().trim(), "Error: system name can not be empty.","stderr");
		} else {
			Assert.assertEquals(result.getStdout().trim(), "Error: system name can not be empty.","stdout");
		}
	}

	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(description = "verify if CLI auto-subscribe tries to re-use basic auth credentials.",
			groups = { "VerifyAutosubscribeReuseBasicAuthCredntials","blockedByBug-707641","blockedByBug-919700"}, enabled = true)
	public void VerifyAutosubscribeReuseBasicAuthCredntials() throws JSONException,Exception {
		clienttasks.register(sm_clientUsername, sm_clientPassword,sm_clientOrg, null, null, null, null, true, null, null,
				(String) null, null, null, null, true, null, null, null, null);	
		String LogMarker = System.currentTimeMillis()+" Testing ***************************************************************";
		// don't think this line does anything		client.runCommandAndWait("ssh root@"+sm_serverHostname);
		RemoteFileTasks.markFile(server, servertasks.tomcat6LogFile, LogMarker);
		String logMessage=" Authentication check for /consumers/"+clienttasks.getCurrentConsumerId()+"/entitlements";
		Assert.assertTrue(RemoteFileTasks.getTailFromMarkedFile(server,servertasks.tomcat6LogFile, LogMarker, logMessage).trim().equals(""));

	}

	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	//To be tested against stage
	@Test(description = "verify if 500 errors in stage on subscribe/unsubscribe",
			groups = {"AcceptanceTests","Tier1Tests","blockedByBug-878994"},
			enabled = true)
	public void Verify500ErrorOnStage() throws JSONException,Exception {
		if(!sm_serverType.equals(CandlepinType.hosted)) throw new SkipException("To be run against Stage only");
		/* jsefler - we should not connect to a hosted candlepin server
		//server=new SSHCommandRunner(sm_serverHostname, sm_sshUser, sm_sshKeyPrivate,sm_sshkeyPassphrase,null);
		server.runCommandAndWait("find "+sm_serverInstallDir+servertasks.generatedProductsDir+" -name '*.pem'");
		clienttasks.unregister(null, null, null);
		log.info("Fetching the generated product certs...");
		 */
		String logMessage = "remote server status code: 500";
		/* jsefler - test with the script's input automation properties, not hardcoded credentials 
		String serverurl="subscription.rhn.stage.redhat.com:443/subscription";
		String clientUsername="stage_test_12";
		clienttasks.register(clientUsername, sm_rhuiPassword,null, null, null, null, null, null, null, null,
				(String) null, serverurl, null, null, null, null, null, null, null).getStdout();
		 */	
		String serverUrl= getServerUrl(clienttasks.getConfFileParameter(clienttasks.rhsmConfFile,"hostname"), clienttasks.getConfFileParameter(clienttasks.rhsmConfFile,"port"), clienttasks.getConfFileParameter(clienttasks.rhsmConfFile,"prefix"));
		clienttasks.register(sm_clientUsername, sm_clientPassword,sm_clientOrg, null, null, null, null, null, null, null, (String) null, serverUrl, null, null, null, null, null, null, null);
		String LogMarker = System.currentTimeMillis()+" Testing ***************************************************************";
		RemoteFileTasks.markFile(client, clienttasks.rhsmLogFile, LogMarker);
		//		String result=clienttasks.listAvailableSubscriptionPools().getStdout();
		String result=clienttasks.list_(null, true, null, null, null, null, null, null, null, null, null, null, null).getStdout();
		Assert.assertTrue(RemoteFileTasks.getTailFromMarkedFile(client, clienttasks.rhsmLogFile, LogMarker, logMessage).trim().equals(""));
		Assert.assertNoMatch(result.trim(), clienttasks.msg_NetworkErrorCheckConnection);
		RemoteFileTasks.markFile(client, clienttasks.rhsmLogFile, LogMarker);
		result=clienttasks.subscribe_(true,(String)null,(String)null,(String)null, null, null, null, null, null, null, null, null).getStdout();
		Assert.assertTrue(RemoteFileTasks.getTailFromMarkedFile(client, clienttasks.rhsmLogFile, LogMarker, logMessage).trim().equals(""));
		Assert.assertNoMatch(result.trim(), clienttasks.msg_NetworkErrorCheckConnection);
		RemoteFileTasks.markFile(client, clienttasks.rhsmLogFile, LogMarker);
		result=clienttasks.unregister(null, null, null).getStdout();
		Assert.assertTrue(RemoteFileTasks.getTailFromMarkedFile(client, clienttasks.rhsmLogFile, LogMarker, logMessage).trim().equals(""));
		Assert.assertNoMatch(result.trim(), clienttasks.msg_NetworkErrorCheckConnection);
	}

	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(description = "verify if redhat repo is created subscription-manager yum plugin when the repo is not present", 
			groups = { "RedhatrepoNotBeingCreated","blockedByBug-886992","blockedByBug-919700"}, enabled = true)
	public void RedhatrepoNotBeingCreated() throws JSONException,Exception {
		client.runCommandAndWait("mv /etc/yum.repos.d/redhat.repo /root/").getStdout();
		List<String[]> listOfSectionNameValues = new ArrayList<String[]>();
		listOfSectionNameValues.add(new String[] { "rhsm",
				"manage_repos".toLowerCase(), "1" });
		clienttasks.config(null, null, true, listOfSectionNameValues);
		clienttasks.register(sm_clientUsername, sm_clientPassword,sm_clientOrg, null, null, null, null, null, null, null,
				(String) null, null, null, null, true, null, null, null, null);
		clienttasks.subscribe(true, null, (String)null, null, null, null, null, null, null, null, null, null);
		Assert.assertTrue(RemoteFileTasks.testExists(client,"/etc/yum.repos.d/redhat.repo"));
		String result=client.runCommandAndWait("yum repolist all").getStdout();
		Assert.assertContainsMatch(result, "repo id");
	}


	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(description = "verify if  insecure in rhsm.comf getse updated when using --insecure option if command fails", 
			groups = { "InsecureValueInRHSMConfAfterRegistrationFailure","blockedByBug-916369"}, enabled = true)
	public void InsecureValueInRHSMConfAfterRegistrationFailure() throws JSONException,Exception {
		String defaultHostname = "rhel7.com";
		String defaultPort = "8443";
		String defaultPrefix = "candlepin";
		String org="foo";
		String valueBeforeRegister=clienttasks.getConfFileParameter(clienttasks.rhsmConfFile, "insecure");
		clienttasks.register_(sm_clientUsername, sm_clientPassword,org, null, null, null, null, null, null, null,(String) null,defaultHostname+":"+defaultPort+"/"+defaultPrefix, null, null, null, null, null, null, null);
		String valueAfterRegister = clienttasks.getConfFileParameter(clienttasks.rhsmConfFile, "insecure");
		Assert.assertEquals(valueBeforeRegister, valueAfterRegister);
	}

	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(description = "verify if subscription-manager register fails with consumerid and activationkey specified", 
			groups = { "RegisterActivationKeyAndConsumerID","blockedByBug-749636"}, enabled = true)
	public void RegisterActivationKeyAndConsumerID() throws JSONException,Exception {
		String name = String.format("%s_%s-ActivationKey%s", sm_clientUsername,
				sm_clientOrg, System.currentTimeMillis());
		Map<String, String> mapActivationKeyRequest = new HashMap<String, String>();
		mapActivationKeyRequest.put("name", name);
		JSONObject jsonActivationKeyRequest = new JSONObject(
				mapActivationKeyRequest);
		JSONObject jsonActivationKey = new JSONObject(
				CandlepinTasks.postResourceUsingRESTfulAPI(sm_clientUsername,
						sm_clientPassword, sm_serverUrl, "/owners/"
								+ sm_clientOrg + "/activation_keys",
								jsonActivationKeyRequest.toString()));
		clienttasks.register(sm_clientUsername, sm_clientPassword,
				sm_clientOrg, null, null, null, null, null, null, null,
				(String) null, null, null, null, true, null, null, null, null);
		String consumerID=clienttasks.getCurrentConsumerId();
		clienttasks.unregister(null, null, null);
		SSHCommandResult registerResult=clienttasks.register_(sm_clientUsername, sm_clientPassword,
				sm_clientOrg, null, null, null, consumerID, null, null, null,
				jsonActivationKey.get("name").toString(), null, null, null, null, null, null, null, null);
		String expected="Error: Activation keys do not require user credentials.";
		if (clienttasks.isPackageVersion("subscription-manager",">=","1.13.9-1")) {	// post commit a695ef2d1da882c5f851fde90a24f957b70a63ad
			Assert.assertEquals(registerResult.getStderr().trim(), expected, "stderr");	
		} else {
			Assert.assertEquals(registerResult.getStdout().trim(), expected, "stdout");	
		}
	}

	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(description = "verify if Product id is displayed in intsalled list", 
			groups = { "ProductIdInInstalledList","blockedByBug-803386"}, enabled = true)
	public void ProductIdInInstalledList() throws JSONException,Exception {
		clienttasks.register(sm_clientUsername, sm_clientPassword,
				sm_clientOrg, null, null, null, null, null, null, null,
				(String) null, null, null, null, true, null, null, null, null);
		for(InstalledProduct result:clienttasks.getCurrentlyInstalledProducts()){
			Assert.assertNotNull(result.productId);

		}

	}


	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	// TODO correct the pasted description
	@Test(description = "verify if Entitlement certs are downloaded if subscribed to expired pool", 
			groups = { "ServerURLInRHSMFile","blockedByBug-916353"}, enabled = true)
	public void ServerURLInRHSMFile() throws JSONException,Exception {
		String defaultHostname = "rhel7.com";
		String defaultPort = "8443";
		String defaultPrefix = "/candlepin";
		String org="foo";
		String valueBeforeRegister=clienttasks.getConfFileParameter(clienttasks.rhsmConfFile, "hostname");
		clienttasks.register_(sm_clientUsername, sm_clientPassword,org, null, null, null, null, null, null, null,(String) null,defaultHostname+":"+defaultPort+"/"+defaultPrefix, null, null, null, null, null, null, null);
		String valueAfterRegister = clienttasks.getConfFileParameter(clienttasks.rhsmConfFile, "hostname");
		Assert.assertEquals(valueBeforeRegister, valueAfterRegister);

	}


	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(description = "Implicitly using the consumer cert from a currently registered system, attempt to query the available service levels on a different candlepin server.", 
			groups = {"DipslayServicelevelWhenRegisteredToDifferentServer","blockedByBug-916362"}, enabled = true)
	public void DisplayServicelevelWhenRegisteredToDifferentServer() {
		String defaultHostname = "subscription.rhn.redhat.com";
		String defaultPort = "443";
		String defaultPrefix = "/subscription";
		clienttasks.register(sm_clientUsername, sm_clientPassword,
				sm_clientOrg, null, null, null, null, null, null, null,
				(String) null, null, null, null, true, null, null, null, null);
		SSHCommandResult result=clienttasks.service_level_(null, null, null, null, null, null, null,defaultHostname+":"+defaultPort+defaultPrefix , null, null, null, null);
		String expectedResult = "You are already registered to a different system";
		if (/*bug 916362 is CLOSED NOTABUG is */true) {
			log.warning("Altering the original expected result '"+expectedResult+"' since Bug 916362 has been CLOSED NOTABUG.");
			log.warning("For more explanation see https://bugzilla.redhat.com/show_bug.cgi?id=916362#c3");
			expectedResult = "Unable to verify server's identity: tlsv1 alert unknown ca";
		}
		if (clienttasks.isPackageVersion("subscription-manager",">=","1.15.0-1")) expectedResult = "Invalid credentials.";	// Not sure of the exact commit, but this is technically the best expected result since you are trying to query the service level on a different server using the consumer cert from a different candlepin server.

		
		if (clienttasks.isPackageVersion("subscription-manager",">=","1.13.9-1")) {	// post commit a695ef2d1da882c5f851fde90a24f957b70a63ad
			Assert.assertEquals(result.getStderr().trim(), expectedResult,"stderr");	
		} else {
			Assert.assertEquals(result.getStdout().trim(), expectedResult,"stdout");	
		}

	}

	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(description = "verify expiration of entitlement certs", 
			groups = {"ExpirationOfEntitlementCerts","blockedByBug-907638","blockedByBug-953830"}, enabled = true)
	public void ExpirationOfEntitlementCerts() throws JSONException,Exception {
		int endingMinutesFromNow =1;
		clienttasks.register(sm_clientUsername, sm_clientPassword,
				sm_clientOrg, null, null, null, null, null, null, null,
				(String) null, null, null, null, true, false, null, null, null);

		String consumerId = clienttasks.getCurrentConsumerId();
		ownerKey = CandlepinTasks.getOwnerKeyOfConsumerId(sm_serverAdminUsername, sm_serverAdminPassword, sm_serverUrl, consumerId);
		Calendar endCalendar = new GregorianCalendar();
		endCalendar.add(Calendar.MINUTE, endingMinutesFromNow);
		Date endDate = endCalendar.getTime();	// caution - if the next call to createTestPool does not occur within this minute; endDate will be 1 minute behind reality
		String expiringPoolId = createTestPool(-60*24,endingMinutesFromNow);
		SubscriptionPool expiringSubscriptionPool = SubscriptionPool.findFirstInstanceWithMatchingFieldFromList("poolId", expiringPoolId, clienttasks.getCurrentlyAvailableSubscriptionPools());

		// attaching from the pool that is about to expire should still be successful
		File expiringEntitlementFile = clienttasks.subscribeToSubscriptionPool(expiringSubscriptionPool,sm_serverAdminUsername, sm_serverAdminPassword, sm_serverUrl);
		EntitlementCert expiringEntitlementCert = clienttasks.getEntitlementCertFromEntitlementCertFile(expiringEntitlementFile);
		clienttasks.unsubscribeFromSerialNumber(expiringEntitlementCert.serialNumber);

		// wait for the pool to expire
		sleep(endingMinutesFromNow*60*1000);

		// attempt to attach an entitlement from an expired pool
		String result=clienttasks.subscribe_(null, null, expiringPoolId, null, null, null, null, null, null, null, null, null).getStdout();	
		// Stdout: Unable to attach pool with ID '8a908740438be86501438cd57718376c'.: Subscriptions for awesomeos-onesocketib expired on: 1/3/14 1:21 PM.
		String expiredOnDatePattern = "M/d/yy h:mm a";	//	1/3/14 1:21 PM
		DateFormat expiredOnDateFormat = new SimpleDateFormat(expiredOnDatePattern);
		String expiredOnString = expiredOnDateFormat.format(endDate.getTime());
		/* 
		String expected="Unable to entitle consumer to the pool with id '"+expiringPoolId+"'.: Subscriptions for "+productId+" expired on: "+EndingDate;
		 */
		String expected = String.format("Unable to attach pool with ID '%s'.: Subscriptions for %s expired on: %s.",expiringSubscriptionPool.poolId,expiringSubscriptionPool.productId,expiredOnString);
		Assert.assertEquals(result.trim(), expected);
	}

	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(description = "verify if Entitlement certs are downloaded if subscribed to expired pool", 
			groups = {"SubscribeToexpiredEntitlement","blockedByBug-907638"}, enabled = true)
	public void SubscribeToexpiredEntitlement() throws JSONException,Exception {
		/* unnecessary
		List<String[]> listOfSectionNameValues = new ArrayList<String[]>();
		listOfSectionNameValues.add(new String[] { "rhsmcertd",
				"certCheckInterval".toLowerCase(), "1" });
		listOfSectionNameValues.add(new String[] { "rhsmcertd",
				"autoAttachInterval".toLowerCase(), "1440" });
		clienttasks.config(null, null, true, listOfSectionNameValues);
		 */
		int endingMinutesFromNow = 1;
		clienttasks.register(sm_clientUsername, sm_clientPassword,
				sm_clientOrg, null, null, null, null, null, null, null,
				(String) null, null, null, null, true, false, null, null, null);
		String consumerId = clienttasks.getCurrentConsumerId();
		ownerKey = CandlepinTasks.getOwnerKeyOfConsumerId(sm_serverAdminUsername, sm_serverAdminPassword, sm_serverUrl, consumerId);
		String expiringPoolId = createTestPool(-60*24,endingMinutesFromNow);
		sleep(endingMinutesFromNow*60*1000);
		clienttasks.subscribe_(null, null, expiringPoolId, null, null, null, null, null, null, null, null, null);
		Assert.assertTrue(clienttasks.getCurrentlyConsumedProductSubscriptions().isEmpty());
		Assert.assertTrue(clienttasks.getCurrentEntitlementCertFiles().isEmpty());
	}



	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(description = "verify if no multiple repos are created,if subscribed to a product that share one or more engineering subscriptions", 
			groups = { "NoMultipleReposCreated"}, enabled = true)
	public void NoMultipleReposCreated() throws JSONException,Exception {
		clienttasks.register(sm_clientUsername, sm_clientPassword,
				sm_clientOrg, null, null, null, null, null, null, null,
				(String) null, null, null, null, true, null, null, null, null);
		String consumerId = clienttasks.getCurrentConsumerId();
		ownerKey = CandlepinTasks.getOwnerKeyOfConsumerId(sm_serverAdminUsername, sm_serverAdminPassword, sm_serverUrl, consumerId);
		CandlepinTasks.deleteSubscriptionsAndRefreshPoolsUsingRESTfulAPI(sm_serverAdminUsername, sm_serverAdminPassword, sm_serverUrl, sm_clientOrg,"multi-stackable");
		CandlepinTasks.refreshPoolsUsingRESTfulAPI(sm_serverAdminUsername, sm_serverAdminPassword, sm_serverUrl, ownerKey);
		String resourcePath = "/products/multi-stackable";
		if (SubscriptionManagerTasks.isVersion(servertasks.statusVersion, ">=", "2.0.0")) resourcePath = "/owners/"+sm_clientOrg+resourcePath;
		CandlepinTasks.deleteResourceUsingRESTfulAPI(sm_serverAdminUsername,sm_serverAdminPassword, sm_serverUrl,resourcePath);
		Calendar calendar = new GregorianCalendar();	// right now
		Date todaysDate = calendar.getTime();
		calendar.add(Calendar.YEAR, 1);
		calendar.add(Calendar.DATE, 10);
		calendar.set(Calendar.HOUR_OF_DAY, 0);calendar.set(Calendar.MINUTE, 0);calendar.set(Calendar.SECOND, 0);	// avoid times in the middle of the day
		Date futureDate = calendar.getTime();
		Map<String,String> attributes = new HashMap<String,String>();
		attributes.put("sockets", "8");
		attributes.put("arch", "ALL");
		attributes.put("type", "MKT");
		attributes.put("multi-entitlement", "yes");
		attributes.put("stacking_id", "726409");
		List<String> providedProducts = new ArrayList<String>();
		providedProducts.add("100000000000002");
		providedProducts.add("100000000000001");
		CandlepinTasks.createProductUsingRESTfulAPI(sm_serverAdminUsername,sm_serverAdminPassword, sm_serverUrl,sm_clientOrg,"Multi-Stackable", "multi-stackable",1 ,attributes, null);
		String requestBody = CandlepinTasks.createSubscriptionRequestBody(20, todaysDate, futureDate,"multi-stackable", Integer.valueOf(getRandInt()), Integer.valueOf(getRandInt()), providedProducts,null).toString();
		CandlepinTasks.postResourceUsingRESTfulAPI(sm_serverAdminUsername, sm_serverAdminPassword, sm_serverUrl, "/owners/" + ownerKey + "/subscriptions", requestBody);	
		JSONObject jobDetail = CandlepinTasks.refreshPoolsUsingRESTfulAPI(sm_serverAdminUsername, sm_serverAdminPassword, sm_serverUrl, ownerKey);
		CandlepinTasks.waitForJobDetailStateUsingRESTfulAPI(sm_serverAdminUsername,sm_serverAdminPassword,sm_serverUrl,jobDetail,"FINISHED", 5*1000, 1);
		sleep(3*60*1000);
		clienttasks.register(sm_clientUsername, sm_clientPassword,
				sm_clientOrg, null, null, null, null, null, null, null,
				(String) null, null, null, null, true, null, null, null, null);
		for(SubscriptionPool pools:clienttasks.getCurrentlyAvailableSubscriptionPools()){
			if(pools.subscriptionName.equals("Multi-Stackable")){
				clienttasks.subscribe_(null, null,pools.poolId, null, null, null, null, null, null, null, null, null);	
			}
		}
		String productIdOne=null;
		List<Repo> originalRepos =clienttasks.getCurrentlySubscribedRepos();
		for (Repo repo : originalRepos) {
			String productIdTwo=null;
			productIdOne=repo.repoId;
			if(!(productIdTwo==null)){
				Assert.assertNotSame(repo.repoId, productIdOne);
			}
			productIdTwo=productIdOne;
		}
		CandlepinTasks.deleteSubscriptionsAndRefreshPoolsUsingRESTfulAPI(sm_serverAdminUsername, sm_serverAdminPassword, sm_serverUrl, sm_clientOrg,"multi-stackable");
		CandlepinTasks.refreshPoolsUsingRESTfulAPI(sm_serverAdminUsername, sm_serverAdminPassword, sm_serverUrl, ownerKey);
		resourcePath = "/products/multi-stackable";
		if (SubscriptionManagerTasks.isVersion(servertasks.statusVersion, ">=", "2.0.0")) resourcePath = "/owners/"+sm_clientOrg+resourcePath;
		CandlepinTasks.deleteResourceUsingRESTfulAPI(sm_serverAdminUsername,sm_serverAdminPassword, sm_serverUrl,resourcePath);

	}





	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(description = "verify that a content set can be deleted after being added to a product.", 
			groups = {"DeleteContentSourceFromProduct","blockedByBug-687970","blockedByBug-834125"}, enabled = true)
	public void DeleteContentSourceFromProduct() throws JSONException,Exception {
		clienttasks.register(sm_clientUsername, sm_clientPassword,
				sm_clientOrg, null, null, null, null, null, null, null,
				(String) null, null, null, null, true, null, null, null, null);
		List<String> modifiedProductIds=null;
		String contentId= "99999";
		Map<String,String> attributes = new HashMap<String,String>();
		attributes.put("sockets", "8");
		attributes.put("arch", "ALL");
		JSONObject jsonContentResource;
		String requestBody = CandlepinTasks.createContentRequestBody("fooname", contentId, "foolabel", "yum", "Foo Vendor", "/foo/path", "/foo/path/gpg", null, null, null, modifiedProductIds).toString();
		String resourcePath = "/content/";
//		if (SubscriptionManagerTasks.isVersion(servertasks.statusVersion, ">=", "2.0.0")) resourcePath = "/owners/"+sm_clientOrg+resourcePath;
//Why?	CandlepinTasks.postResourceUsingRESTfulAPI(sm_serverAdminUsername, sm_serverAdminPassword, sm_serverUrl,resourcePath, requestBody);	
		resourcePath = "/content/"+contentId;
//		if (SubscriptionManagerTasks.isVersion(servertasks.statusVersion, ">=", "2.0.0")) resourcePath = "/owners/"+sm_clientOrg+resourcePath;
//Why?		jsonContentResource = new JSONObject(CandlepinTasks.getResourceUsingRESTfulAPI(sm_serverAdminUsername, sm_serverAdminPassword, sm_serverUrl, resourcePath));
		String contentWithIdMessage = "Content with id "+contentId+" could not be found.";
		if (SubscriptionManagerTasks.isVersion(servertasks.statusVersion, ">=", "2.0.7")) contentWithIdMessage = "Content with ID \""+contentId+"\" could not be found.";	// commit 6b63e346c61789837211828043ad9576a756d0e8
		
//Why?	Assert.assertContainsNoMatch(jsonActivationKey.toString(), contentWithIdMessage);
		resourcePath = "/content/"+contentId;
		if (SubscriptionManagerTasks.isVersion(servertasks.statusVersion, ">=", "2.0.0")) resourcePath = "/owners/"+sm_clientOrg+resourcePath;
		CandlepinTasks.deleteResourceUsingRESTfulAPI(sm_serverAdminUsername, sm_serverAdminPassword, sm_serverUrl, resourcePath);
		/* restart_rhsmcertd has nothing to do with testing the ability to DELETE a content resource
		clienttasks.restart_rhsmcertd(null, null, null);
		sleep(2*60*1000);
		 */
		resourcePath = "/content/"+contentId;
		if (SubscriptionManagerTasks.isVersion(servertasks.statusVersion, ">=", "2.0.0")) resourcePath = "/owners/"+sm_clientOrg+resourcePath;
		jsonContentResource = new JSONObject(CandlepinTasks.getResourceUsingRESTfulAPI(sm_serverAdminUsername, sm_serverAdminPassword, sm_serverUrl, resourcePath));
		Assert.assertEquals(jsonContentResource.getString("displayMessage"), contentWithIdMessage);
		requestBody = CandlepinTasks.createContentRequestBody("fooname", contentId, "foolabel", "yum", "Foo Vendor", "/foo/path", "/foo/path/gpg", null, null, null, modifiedProductIds).toString();
		resourcePath = "/content";
		if (SubscriptionManagerTasks.isVersion(servertasks.statusVersion, ">=", "2.0.0")) resourcePath = "/owners/"+sm_clientOrg+resourcePath;
		CandlepinTasks.postResourceUsingRESTfulAPI(sm_serverAdminUsername, sm_serverAdminPassword, sm_serverUrl,resourcePath, requestBody);
		resourcePath = "/products/fooproduct";
		if (SubscriptionManagerTasks.isVersion(servertasks.statusVersion, ">=", "2.0.0")) resourcePath = "/owners/"+sm_clientOrg+resourcePath;
		CandlepinTasks.deleteResourceUsingRESTfulAPI(sm_serverAdminUsername, sm_serverAdminPassword, sm_serverUrl,resourcePath);	// in case it already exists from prior run
		CandlepinTasks.createProductUsingRESTfulAPI(sm_serverAdminUsername,sm_serverAdminPassword, sm_serverUrl,sm_clientOrg,"fooname", "fooproduct",null ,attributes, null);
		resourcePath = "/products/fooproduct/content/"+contentId;
		if (SubscriptionManagerTasks.isVersion(servertasks.statusVersion, ">=", "2.0.0")) resourcePath = "/owners/"+sm_clientOrg+resourcePath;
		CandlepinTasks.postResourceUsingRESTfulAPI(sm_serverAdminUsername, sm_serverAdminPassword, sm_serverUrl,resourcePath+"?enabled=false",null);
		resourcePath = "/products/" + "fooproduct";
		if (SubscriptionManagerTasks.isVersion(servertasks.statusVersion, ">=", "2.0.0")) resourcePath = "/owners/"+sm_clientOrg+resourcePath;
		String jsonProduct = CandlepinTasks.getResourceUsingRESTfulAPI(sm_serverAdminUsername, sm_serverAdminPassword, sm_serverUrl, resourcePath);
		Assert.assertContainsMatch(jsonProduct, contentId, "Added content set '"+contentId+"' to product "+"fooproduct");
		resourcePath = "/content/"+contentId;
		if (SubscriptionManagerTasks.isVersion(servertasks.statusVersion, ">=", "2.0.0")) resourcePath = "/owners/"+sm_clientOrg+resourcePath;
		CandlepinTasks.deleteResourceUsingRESTfulAPI(sm_serverAdminUsername, sm_serverAdminPassword, sm_serverUrl, resourcePath);
		resourcePath = "/content/"+contentId;
		if (SubscriptionManagerTasks.isVersion(servertasks.statusVersion, ">=", "2.0.0")) resourcePath = "/owners/"+sm_clientOrg+resourcePath;
		jsonContentResource = new JSONObject(CandlepinTasks.getResourceUsingRESTfulAPI(sm_serverAdminUsername, sm_serverAdminPassword, sm_serverUrl, resourcePath));
		/* This assertion is reinforcing a bug in candlepin.
		 * The prior call to delete content 99999 should always pass even when it has been added to a product.
		 * This was fixed by https://bugzilla.redhat.com/show_bug.cgi?id=834125#c17
		 * Changing the assertion to Assert.assertContainsMatch
		Assert.assertContainsNoMatch(jsonActivationKey.toString(), contentWithIdMessage);
		 */
		Assert.assertEquals(jsonContentResource.getString("displayMessage"), contentWithIdMessage);
		resourcePath = "/products/" + "fooproduct";
		if (SubscriptionManagerTasks.isVersion(servertasks.statusVersion, ">=", "2.0.0")) resourcePath = "/owners/"+sm_clientOrg+resourcePath;
		jsonProduct = CandlepinTasks.getResourceUsingRESTfulAPI(sm_serverAdminUsername, sm_serverAdminPassword, sm_serverUrl, resourcePath);
		Assert.assertContainsNoMatch(jsonProduct, contentId, "After deleting content set '"+contentId+"', it was removed from the product "+"fooproduct");
		resourcePath = "/products/fooproduct";
		if (SubscriptionManagerTasks.isVersion(servertasks.statusVersion, ">=", "2.0.0")) resourcePath = "/owners/"+sm_clientOrg+resourcePath;
		CandlepinTasks.deleteResourceUsingRESTfulAPI(sm_serverAdminUsername,sm_serverAdminPassword, sm_serverUrl,resourcePath);
	}

	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(	description = "verify that bind and unbind event is recorded in syslog", 
			groups = {"VerifyBindAndUnbindInSyslog","blockedByBug-919700"},
			enabled = true)
	@ImplementsNitrateTest(caseId=68740)
	public void VerifyBindAndUnbindInSyslog() throws JSONException,Exception {
		String logMarker, expectedSyslogMessage, tailFromSyslogFile;

		clienttasks.register(sm_clientUsername, sm_clientPassword,sm_clientOrg, null, null, null, null, null, null, null, (String) null, null, null, null, true, false, null, null, null);

		logMarker = System.currentTimeMillis()+" Testing Subscribe **********************";
		RemoteFileTasks.markFile(client, clienttasks.messagesLogFile, logMarker);
		List<SubscriptionPool> pools = clienttasks.getCurrentlyAvailableSubscriptionPools();
		List<String> poolIds = new ArrayList<String>(); for (SubscriptionPool pool : pools) poolIds.add(pool.poolId);
		clienttasks.subscribe(null, null, poolIds, null, null, null, null, null, null, null, null, null);
		tailFromSyslogFile = RemoteFileTasks.getTailFromMarkedFile(client, clienttasks.messagesLogFile, logMarker, null);
		for (SubscriptionPool pool : pools) {
			//	Feb  3 12:08:01 jsefler-7 subscription-manager: Added subscription for 'Awesome OS Stackable guest limit 4' contract '2'
			//	Feb  3 12:08:01 jsefler-7 subscription-manager: Added subscription for product 'Awesome OS Server Bits'
			//	Oct 2 01:20:52 jsefler-7server subscription-manager: Added subscription for 'Awesome OS Instance Based (Standard Support)' contract 'None'
			expectedSyslogMessage = String.format("%s: Added subscription for '%s' contract '%s'", clienttasks.command, pool.subscriptionName,pool.contract.isEmpty()?"None":pool.contract);	// Note that a null/missing contract will be reported as None.  Seems reasonable.
			Assert.assertTrue(tailFromSyslogFile.contains(expectedSyslogMessage),"After subscribing to '"+pool.subscriptionName+"', syslog '"+clienttasks.messagesLogFile+"' contains expected message '"+expectedSyslogMessage+"'.");
			for (String providedProduct : pool.provides) {
				// TEMPORARY WORKAROUND FOR BUG: https://bugzilla.redhat.com/show_bug.cgi?id=1016300
				if (providedProduct.equals("Awesome OS Server Bundled")) {
					boolean invokeWorkaroundWhileBugIsOpen = true;
					String bugId="1016300"; 
					try {if (invokeWorkaroundWhileBugIsOpen&&BzChecker.getInstance().isBugOpen(bugId)) {log.fine("Invoking workaround for "+BzChecker.getInstance().getBugState(bugId).toString()+" Bugzilla "+bugId+".  (https://bugzilla.redhat.com/show_bug.cgi?id="+bugId+")");SubscriptionManagerCLITestScript.addInvokedWorkaround(bugId);} else {invokeWorkaroundWhileBugIsOpen=false;}} catch (XmlRpcException xre) {/* ignore exception */} catch (RuntimeException re) {/* ignore exception */}
					if (invokeWorkaroundWhileBugIsOpen) {
						log.warning("Ignoring the provided MKT product '"+providedProduct+"'.  No syslog assertion for this product will be made.");
						continue;
					}
				}
				// END OF WORKAROUND
				expectedSyslogMessage = String.format("%s: Added subscription for product '%s'", clienttasks.command, providedProduct);
				Assert.assertTrue(tailFromSyslogFile.contains(expectedSyslogMessage),"After subscribing to '"+pool.subscriptionName+"', syslog '"+clienttasks.messagesLogFile+"' contains expected message '"+expectedSyslogMessage+"'.");
			}
		}	

		logMarker = System.currentTimeMillis()+" Testing Unsubscribe **********************";
		RemoteFileTasks.markFile(client, clienttasks.messagesLogFile, logMarker);
		List<ProductSubscription> productSubscriptions = clienttasks.getCurrentlyConsumedProductSubscriptions();
		clienttasks.unsubscribe(true,(BigInteger)null, null, null, null);
		tailFromSyslogFile = RemoteFileTasks.getTailFromMarkedFile(client, clienttasks.messagesLogFile, logMarker, null);
		for (ProductSubscription productSubscription : productSubscriptions) {
			//	Feb  3 13:32:34 jsefler-7 subscription-manager: Removed subscription for 'Awesome OS Server Bundled (2 Sockets, Standard Support)' contract '3'
			//	Feb  3 13:32:34 jsefler-7 subscription-manager: Removed subscription for product 'Clustering Bits'
			//	Feb  3 13:32:34 jsefler-7 subscription-manager: Removed subscription for product 'Awesome OS Server Bits'
			//	Feb  3 13:32:34 jsefler-7 subscription-manager: Removed subscription for product 'Load Balancing Bits'
			//	Feb  3 13:32:34 jsefler-7 subscription-manager: Removed subscription for product 'Large File Support Bits'
			//	Feb  3 13:32:34 jsefler-7 subscription-manager: Removed subscription for product 'Shared Storage Bits'
			//	Feb  3 13:32:34 jsefler-7 subscription-manager: Removed subscription for product 'Management Bits'
			expectedSyslogMessage = String.format("%s: Removed subscription for '%s' contract '%s'", clienttasks.command, productSubscription.productName,productSubscription.contractNumber==null?"None":productSubscription.contractNumber);	// Note that a null/missing contract will be reported as None.  Seems reasonable.
			Assert.assertTrue(tailFromSyslogFile.contains(expectedSyslogMessage),"After unsubscribing from '"+productSubscription.productName+"', syslog '"+clienttasks.messagesLogFile+"' contains expected message '"+expectedSyslogMessage+"'.");
			for (String providedProduct : productSubscription.provides) {
				expectedSyslogMessage = String.format("%s: Removed subscription for product '%s'", clienttasks.command, providedProduct);
				Assert.assertTrue(tailFromSyslogFile.contains(expectedSyslogMessage),"After unsubscribing from '"+productSubscription.productName+"', syslog '"+clienttasks.messagesLogFile+"' contains expected message '"+expectedSyslogMessage+"'.");
			}
		}
	}

	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(description = "verify if register and unregister event is recorded in syslog", 
			groups = {"VerifyRegisterAndUnregisterInSyslog"}, enabled = true)
	@ImplementsNitrateTest(caseId=68749)
	public void VerifyRegisterAndUnregisterInSyslog() throws JSONException,Exception {
		String logMarker, expectedSyslogMessage, tailFromSyslogFile;

		logMarker = System.currentTimeMillis()+" Testing Register **********************";
		RemoteFileTasks.markFile(client, clienttasks.messagesLogFile, logMarker);
		String identity = clienttasks.getCurrentConsumerId(clienttasks.register(sm_clientUsername, sm_clientPassword,sm_clientOrg, null, null, null, null, null, null, null, (String) null, null, null, null, true, false, null, null, null));
		tailFromSyslogFile = RemoteFileTasks.getTailFromMarkedFile(client, clienttasks.messagesLogFile, logMarker, null);
		//	Feb  3 12:50:47 jsefler-7 subscription-manager: Registered system with identity: eddfaf6d-e916-49e3-aa71-e33a2c54e1dd
		expectedSyslogMessage = String.format("%s: Registered system with identity: %s", clienttasks.command, identity);
		Assert.assertTrue(tailFromSyslogFile.contains(expectedSyslogMessage),"After registering', syslog '"+clienttasks.messagesLogFile+"' contains expected message '"+expectedSyslogMessage+"'.");

		logMarker = System.currentTimeMillis()+" Testing Unregister **********************";
		RemoteFileTasks.markFile(client, clienttasks.messagesLogFile, logMarker);
		clienttasks.unregister(null, null, null);	
		tailFromSyslogFile = RemoteFileTasks.getTailFromMarkedFile(client, clienttasks.messagesLogFile, logMarker, null);
		//	Feb  3 13:39:21 jsefler-7 subscription-manager: Unregistered machine with identity: 231c2b52-4bc8-4458-8d0a-252b1dd82877
		expectedSyslogMessage = String.format("%s: Unregistered machine with identity: %s", clienttasks.command, identity);
		Assert.assertTrue(tailFromSyslogFile.contains(expectedSyslogMessage),"After unregistering', syslog '"+clienttasks.messagesLogFile+"' contains expected message '"+expectedSyslogMessage+"'.");
	}


	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(description = "verify that Consumer Account And Contract Id are Present in the consumed list", 
			groups = { "VerifyConsumerAccountAndContractIdPresence"}, enabled = true)
	@ImplementsNitrateTest(caseId=68738)
	public void VerifyConsumerAccountAndContractIdPresence() throws JSONException,Exception {
		clienttasks.register(sm_clientUsername, sm_clientPassword,
				sm_clientOrg, null, null, null, null, null, null, null,
				(String) null, null, null, null, true, null, null, null, null);
		for(ProductSubscription consumed:clienttasks.getCurrentlyConsumedProductSubscriptions()){
			Assert.assertNotNull(consumed.accountNumber);
			Assert.assertNotNull(consumed.contractNumber);

		}
	}
	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(description = "verify that system should not be compliant for an expired subscription", 
			groups = { "VerifySubscriptionOf"}, enabled = false)
	//@ImplementsNitrateTest(caseId=71208)
	public void VerifySubscriptionOfBestProductWithUnattendedRegistration() throws JSONException,Exception {
		Map<String,String> attributes = new HashMap<String,String>();
		List<String[]> listOfSectionNameValues = new ArrayList<String[]>();
		listOfSectionNameValues.add(new String[] { "rhsmcertd",
				"autoAttachInterval".toLowerCase(), "1440" });
		clienttasks.config(null, null, true, listOfSectionNameValues);
		clienttasks.register(sm_clientUsername, sm_clientPassword,
				sm_clientOrg, null, null, null, null, null, null, null,
				(String) null, null, null, null, true, null, null, null, null);
		CandlepinTasks.deleteSubscriptionsAndRefreshPoolsUsingRESTfulAPI(sm_serverAdminUsername, sm_serverAdminPassword, sm_serverUrl, sm_clientOrg,"multi-stackable");
		CandlepinTasks.refreshPoolsUsingRESTfulAPI(sm_serverAdminUsername, sm_serverAdminPassword, sm_serverUrl, ownerKey);
		String resourcePath = "/products/multi-stackable";
		if (SubscriptionManagerTasks.isVersion(servertasks.statusVersion, ">=", "2.0.0")) resourcePath = "/owners/"+sm_clientOrg+resourcePath;
		CandlepinTasks.deleteResourceUsingRESTfulAPI(sm_serverAdminUsername,sm_serverAdminPassword, sm_serverUrl,resourcePath);

		String consumerId = clienttasks.getCurrentConsumerId();
		ownerKey = CandlepinTasks.getOwnerKeyOfConsumerId(sm_serverAdminUsername, sm_serverAdminPassword, sm_serverUrl, consumerId);
		Calendar cal = new GregorianCalendar();
		Date todaysDate = cal.getTime();
		cal.set(Calendar.HOUR_OF_DAY, 0);cal.set(Calendar.MINUTE, 0);cal.set(Calendar.SECOND, 0);	// avoid times in the middle of the day
		cal.add(Calendar.YEAR, 1);
		cal.add(Calendar.DATE, 10);
		Date futureDate = cal.getTime();	// one year and ten days from tomorrow
		attributes.put("sockets", "0");
		attributes.put("arch", "ALL");
		attributes.put("type", "MKT");
		attributes.put("multi-entitlement", "yes");
		attributes.put("stacking_id", "726409");
		List<String> providedProducts = new ArrayList<String>();
		providedProducts.add("100000000000002");
		CandlepinTasks.createProductUsingRESTfulAPI(sm_serverAdminUsername,sm_serverAdminPassword, sm_serverUrl,sm_clientOrg,"Multi-Stackable for 100000000000002", "multi-stackable",1 ,attributes, null);
		String requestBody = CandlepinTasks.createSubscriptionRequestBody(20, todaysDate, futureDate,"multi-stackable", Integer.valueOf(getRandInt()), Integer.valueOf(getRandInt()), providedProducts,null).toString();
		CandlepinTasks.postResourceUsingRESTfulAPI(sm_serverAdminUsername, sm_serverAdminPassword, sm_serverUrl, "/owners/" + ownerKey + "/subscriptions", requestBody);	
		JSONObject jobDetail = CandlepinTasks.refreshPoolsUsingRESTfulAPI(sm_serverAdminUsername, sm_serverAdminPassword, sm_serverUrl, ownerKey);
		jobDetail = CandlepinTasks.waitForJobDetailStateUsingRESTfulAPI(sm_serverAdminUsername,sm_serverAdminPassword,sm_serverUrl,jobDetail,"FINISHED", 5*1000, 1);
		clienttasks.unsubscribeFromAllOfTheCurrentlyConsumedProductSubscriptions();
		sleep(3*60*1000);
		int sockets=16;
		Map<String, String> factsMap = new HashMap<String, String>();
		factsMap.put("lscpu.cpu_socket(s)", String.valueOf(sockets));
		factsMap.put("cpu.cpu_socket(s)", String.valueOf(sockets));
		clienttasks.createFactsFileWithOverridingValues(factsMap);

		clienttasks.register(sm_clientUsername, sm_clientPassword,
				sm_clientOrg, null, null, null, null, true, null, null,
				(String) null, null, null, null, true, null, null, null, null);
		InstalledProduct installedProduct = InstalledProduct.findFirstInstanceWithMatchingFieldFromList("productId", "100000000000002", clienttasks.getCurrentlyInstalledProducts());

		if(installedProduct.productId.equals("100000000000002"))
			Assert.assertEquals(installedProduct.status, "Subscribed");


		for(ProductSubscription consumed:clienttasks.getCurrentlyConsumedProductSubscriptions()){
			Assert.assertEquals(consumed.productName, "Multi-Stackable for 100000000000002");
		}



	}


	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(description = "verify that system should not be compliant for an expired subscription", 
			groups = {"VerifySystemCompliantFact"}, enabled = false)
	public void VerifySystemCompliantFactWhenAllProductsAreExpired_Test() throws JSONException,Exception {

		List<ProductCert> productCerts = clienttasks.getCurrentProductCerts();
		clienttasks.register(sm_clientUsername, sm_clientPassword,
				sm_clientOrg, null, null, null, null, null, null, null,
				(String) null, null, null, null, true, false, null, null, null);

		File expectCertFile = new File(System.getProperty("automation.dir",
				null) + "/expiredcerts/Expiredcert.pem");
		RemoteFileTasks.putFile(client.getConnection(),
				expectCertFile.toString(), "/root/", "0755");
		clienttasks.importCertificate_("/root/Expiredcert.pem");
		for (InstalledProduct installed : clienttasks.getCurrentlyInstalledProducts()) {
			if(!(installed.status.equals("Expired"))){
				ProductCert productCert = ProductCert.findFirstInstanceWithMatchingFieldFromList("productId", installed.productId, productCerts);
				moveProductCertFiles(productCert.file.getName());
			}
		}
		clienttasks.facts(null,true,null,null,null); 
		List<InstalledProduct> currentlyInstalledProducts = clienttasks.getCurrentlyInstalledProducts();
		Assert.assertEquals(currentlyInstalledProducts.size(),1,"Expecting one installed product provided by the expired entitlement just imported.");
		String actual=clienttasks.getFactValue(factname).trim();
		Assert.assertEquals(actual, "invalid","Value of system fact '"+factname+"'.");
	}

	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(description = "verify if able to entitle consumer to the pool virt_only,pool_derived,bonus pool ", 
			groups = {"VerifyVirtOnlyPoolsRemoved","blockedByBug-887287"}, enabled = true)		
	public void VerifyVirtOnlyPoolsRemoved() throws JSONException,Exception {
		clienttasks.register(sm_clientUsername, sm_clientPassword,
				sm_clientOrg, null, null, null, null, null, null, null,
				(List<String>)null, (String)null, null, null, true, null, null, null, null);
		String consumerId = clienttasks.getCurrentConsumerId();
		ownerKey = CandlepinTasks.getOwnerKeyOfConsumerId(sm_clientUsername, sm_clientPassword, sm_serverUrl, consumerId);
		Calendar cal = new GregorianCalendar();	// right now
		cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0);// avoid times in the middle of the day
		Date todaysDate = cal.getTime();
		cal.add(Calendar.YEAR, 1);
		cal.add(Calendar.DATE, 10);
		Date futureDate = cal.getTime();	// one year and 10 days from tomorrow
		Map<String,String> attributes = new HashMap<String,String>();
		attributes.put("virt_limit", "4");
		attributes.put("arch", "ALL");
		attributes.put("type", "MKT");
		attributes.put("multi-entitlement", "no");
		List<String> providedProducts = new ArrayList<String>();
		providedProducts.add("27060");
		CandlepinTasks.deleteResourceUsingRESTfulAPI(sm_serverAdminUsername,sm_serverAdminPassword,sm_serverUrl,"/subscriptions/"+"virtualPool");
		String resourcePath = "/products/virtual-pool";
		if (SubscriptionManagerTasks.isVersion(servertasks.statusVersion, ">=", "2.0.0")) resourcePath = "/owners/"+sm_clientOrg+resourcePath;
		CandlepinTasks.deleteResourceUsingRESTfulAPI(sm_serverAdminUsername,sm_serverAdminPassword,sm_serverUrl,resourcePath);

		CandlepinTasks.createProductUsingRESTfulAPI(sm_serverAdminUsername,sm_serverAdminPassword, sm_serverUrl,sm_clientOrg,"virtual-product", "virtualPool",1 ,attributes, null);
		String requestBody = CandlepinTasks.createSubscriptionRequestBody(10, todaysDate, futureDate,"virtualPool", Integer.valueOf(getRandInt()), Integer.valueOf(getRandInt()), providedProducts,null).toString();
		CandlepinTasks.postResourceUsingRESTfulAPI(sm_serverAdminUsername, sm_serverAdminPassword, sm_serverUrl, "/owners/" + ownerKey + "/subscriptions", requestBody);	
		JSONObject jobDetail = CandlepinTasks.refreshPoolsUsingRESTfulAPI(sm_serverAdminUsername, sm_serverAdminPassword, sm_serverUrl, ownerKey);
		CandlepinTasks.waitForJobDetailStateUsingRESTfulAPI(sm_serverAdminUsername,sm_serverAdminPassword,sm_serverUrl,jobDetail,"FINISHED", 5*1000, 1);
		boolean flag=false;
		String poolId=null;
		for(SubscriptionPool pools:clienttasks.getCurrentlyAvailableSubscriptionPools()){
			if(pools.subscriptionName.equals("virtual-product")){
				flag=true;
				poolId=pools.poolId;
			}
		}
		Assert.assertTrue(flag, "Pool is created");
		if(clienttasks.getFactValue("virt.is_guest").equals("False")){
			clienttasks.subscribe(null, null, poolId, null, null, "1", null, null, null, null, null, null);

			List<String> poolsAvailableForParticularProduct=CandlepinTasks.getPoolIdsForProductId(sm_serverAdminUsername, sm_serverAdminPassword, sm_serverUrl, sm_clientOrg, "virtualPool");
			Assert.assertEquals(poolsAvailableForParticularProduct.size(), 2);	
			clienttasks.unsubscribeFromTheCurrentlyConsumedProductSubscriptionsCollectively();

			String sub="{\"attributes\":[{\"name\":\"virt_limit\",\"value\":\"0\"}]}";
			JSONObject jsonData= new JSONObject(sub);

			CandlepinTasks.putResourceUsingRESTfulAPI(sm_serverAdminUsername,sm_serverAdminPassword, sm_serverUrl, "/products/virtualPool", jsonData);

			jobDetail = CandlepinTasks.refreshPoolsUsingRESTfulAPI(sm_serverAdminUsername, sm_serverAdminPassword, sm_serverUrl, ownerKey);
			CandlepinTasks.waitForJobDetailStateUsingRESTfulAPI(sm_serverAdminUsername,sm_serverAdminPassword,sm_serverUrl,jobDetail,"FINISHED", 5*1000, 1);

			clienttasks.subscribe(null, null, poolId, null, null, "1", null, null, null, null, null, null);

			List<String> poolsAvailableForParticularProductAfterSettingVirt_limit=CandlepinTasks.getPoolIdsForProductId(sm_serverAdminUsername, sm_serverAdminPassword, sm_serverUrl, sm_clientOrg, "virtualPool");
			Assert.assertEquals(poolsAvailableForParticularProductAfterSettingVirt_limit.size(), 1);
			clienttasks.unsubscribeFromTheCurrentlyConsumedProductSubscriptionsCollectively();
			sub="{\"attributes\":[{\"name\":\"virt_limit\",\"value\":\"null\"}]}";
			jsonData= new JSONObject(sub);
			CandlepinTasks.putResourceUsingRESTfulAPI(sm_serverAdminUsername,sm_serverAdminPassword, sm_serverUrl, "/products/virtualPool", jsonData);

			jobDetail = CandlepinTasks.refreshPoolsUsingRESTfulAPI(sm_serverAdminUsername, sm_serverAdminPassword, sm_serverUrl, ownerKey);
			CandlepinTasks.waitForJobDetailStateUsingRESTfulAPI(sm_serverAdminUsername,sm_serverAdminPassword,sm_serverUrl,jobDetail,"FINISHED", 5*1000, 1);

			clienttasks.subscribe(null, null, poolId, null, null, "1", null, null, null, null, null, null);

			List<String> poolsAvailableForParticularProductAfterSettingVirt_limitToNull=CandlepinTasks.getPoolIdsForProductId(sm_serverAdminUsername, sm_serverAdminPassword, sm_serverUrl, sm_clientOrg, "virtualPool");
			Assert.assertEquals(poolsAvailableForParticularProductAfterSettingVirt_limitToNull.size(), 1);
		}else{throw new SkipException("no Instance based subscriptions are available for testing");

		}}

	public  String getSubscriptionID(String authenticator, String password, String url, String ownerKey, String productId) throws JSONException, Exception  {
		String subscriptionId =null;
		JSONArray jsonSubscriptions = new JSONArray(CandlepinTasks.getResourceUsingRESTfulAPI(authenticator,password,url,"/owners/"+ownerKey+"/subscriptions"));	
		for (int i = 0; i < jsonSubscriptions.length(); i++) {
			JSONObject jsonSubscription = (JSONObject) jsonSubscriptions.get(i);

			JSONObject jsonProduct = (JSONObject) jsonSubscription.getJSONObject("product");
			String productName = jsonProduct.getString("name");
			if (productId.equals(jsonProduct.getString("id"))) {
				subscriptionId =jsonSubscription.getString("id");
			}}
		return subscriptionId;
	}



	@AfterGroups(groups = "setup", value = {"VerifyVirtOnlyPoolsRemoved"}, enabled = true)
	public void cleanupAfterVerifyVirtOnlyPoolsRemoved() throws Exception{
		// TODO This is not completely accurate, but it is a good place to cleanup after VerifyVirtOnlyPoolsRemoved...
		CandlepinTasks.deleteResourceUsingRESTfulAPI(sm_serverAdminUsername,sm_serverAdminPassword,sm_serverUrl,"/subscriptions/"+"virtualPool");
		
		String resourcePath = "/products/virtual-pool";
		if (SubscriptionManagerTasks.isVersion(servertasks.statusVersion, ">=", "2.0.0")) resourcePath = "/owners/"+sm_clientOrg+resourcePath;
		CandlepinTasks.deleteResourceUsingRESTfulAPI(sm_serverAdminUsername,sm_serverAdminPassword,sm_serverUrl,resourcePath);
		JSONObject jobDetail = CandlepinTasks.refreshPoolsUsingRESTfulAPI(sm_serverAdminUsername,sm_serverAdminPassword,sm_serverUrl,sm_clientOrg);
		jobDetail = CandlepinTasks.waitForJobDetailStateUsingRESTfulAPI(sm_serverAdminUsername,sm_serverAdminPassword,sm_serverUrl,jobDetail,"FINISHED", 5*1000, 1);
	}

	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(description = "verify if able to entitle consumer to the pool virt_only,pool_derived,bonus pool ", 
			groups = { "consumeVirtOnlyPool","blockedByBug-756628"}, enabled = true)
	public void consumeVirtOnlyPool() throws JSONException,Exception {
		String isPool_derived =null;
		Boolean virtonly=false;
		clienttasks.register(sm_clientUsername, sm_clientPassword,
				sm_clientOrg, null, null, null, null, null, null, null,
				(List<String>)null,  (String)null, null, null, true, null, null, null, null);
		String isGuest=clienttasks.getFactValue("virt.is_guest");
		if(isGuest.equalsIgnoreCase("true")){
			Map<String, String> factsMap = new HashMap<String, String>();
			factsMap.put("virt.is_guest", "False");
			clienttasks.facts(null, true, null, null, null);
			clienttasks.createFactsFileWithOverridingValues(factsMap);
			for (SubscriptionPool availList : clienttasks.getCurrentlyAvailableSubscriptionPools()) {
				isPool_derived = CandlepinTasks.getPoolProductAttributeValue(sm_clientUsername,	sm_clientPassword, sm_serverUrl, availList.poolId,"pool_derived");		
				virtonly= CandlepinTasks.isPoolVirtOnly(sm_clientUsername, sm_clientPassword, availList.poolId, sm_serverUrl);
				if(!(isPool_derived==null) || virtonly){
					String result= clienttasks.subscribe_(null, null, availList.poolId, null, null, null, null,null,null, null,null, null).getStdout();
					String Expected="Pool is restricted to virtual guests: "+availList.subscriptionName;
					Assert.assertEquals(result.trim(), Expected);
				}
			}
		}


	}





	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(description = "verify if system.entitlements_valid goes from valid to partial after oversubscribing", // TODO fix this description
			groups = {"VerifyRHELWorkstationSubscription","blockedByBug-739790"}, enabled = true)
	public void VerifyRHELWorkstationSubscription() throws JSONException,Exception {
		InstalledProduct workstation = InstalledProduct.findFirstInstanceWithMatchingFieldFromList("productId", "71", clienttasks.getCurrentlyInstalledProducts());
		if (workstation==null) throw new SkipException("This test is only applicable on a RHEL Workstation where product 71 is installed.");
		clienttasks.register(sm_clientUsername, sm_clientPassword,
				sm_clientOrg, null, null, null, null, null, null, null,
				(List<String>)null, (String)null, null, null, true, false, null, null, null);
		/* too time consuming; replace with subscribeToTheCurrentlyAvailableSubscriptionPoolsCollectively();	
		for (SubscriptionPool availList : clienttasks
				.getCurrentlyAvailableSubscriptionPools()) {
				clienttasks.subscribe_(null, null, availList.poolId, null, null, null, null, null, null, null, null);
		}
		 */
		clienttasks.subscribeToTheCurrentlyAvailableSubscriptionPoolsCollectively();
		boolean assertedWorkstationProduct = false;
		for (InstalledProduct installed : clienttasks
				.getCurrentlyInstalledProducts()) {
			if(installed.productId.contains("Workstation")){
				Assert.assertEquals(installed.status, "subscribed");
				assertedWorkstationProduct = true;
			}
		}
		if (!assertedWorkstationProduct) throw new SkipException("Installed product to be tested is not available");
	}




	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(description = "verify OwnerInfo is displayed only for pools that are active right now, for all the stats", // TODO, correct this description
			groups = { "certificateStacking","blockedByBug-726409","blockedByBug-1183175"}, enabled = true)
	public void certificateStacking() throws JSONException,Exception {
		Map<String,String> attributes = new HashMap<String,String>();
		clienttasks.register(sm_clientUsername, sm_clientPassword,
				sm_clientOrg, null, null, null, null, null, null, null,
				(List<String>)null, (String) null, null, null, true, null, null, null,null);
		ProductCert installedProductCert100000000000002 = ProductCert.findFirstInstanceWithMatchingFieldFromList("productId", "100000000000002", clienttasks.getCurrentProductCerts());
		Assert.assertNotNull(installedProductCert100000000000002, "Found installed product cert 100000000000002 needed for this test.");
		configureTmpProductCertDirWithInstalledProductCerts(Arrays.asList(new ProductCert[]{installedProductCert100000000000002}));

		CandlepinTasks.deleteSubscriptionsAndRefreshPoolsUsingRESTfulAPI(sm_serverAdminUsername, sm_serverAdminPassword, sm_serverUrl, sm_clientOrg,"multi-stackable");
		CandlepinTasks.refreshPoolsUsingRESTfulAPI(sm_serverAdminUsername, sm_serverAdminPassword, sm_serverUrl, ownerKey);
		String resourcePath = "/products/multi-stackable";
		if (SubscriptionManagerTasks.isVersion(servertasks.statusVersion, ">=", "2.0.0")) resourcePath = "/owners/"+sm_clientOrg+resourcePath;
		CandlepinTasks.deleteResourceUsingRESTfulAPI(sm_serverAdminUsername,sm_serverAdminPassword, sm_serverUrl,resourcePath);
		CandlepinTasks.deleteSubscriptionsAndRefreshPoolsUsingRESTfulAPI(sm_serverAdminUsername, sm_serverAdminPassword, sm_serverUrl, sm_clientOrg,"stackable");
		CandlepinTasks.refreshPoolsUsingRESTfulAPI(sm_serverAdminUsername, sm_serverAdminPassword, sm_serverUrl, ownerKey);
		resourcePath = "/products/stackable";
		if (SubscriptionManagerTasks.isVersion(servertasks.statusVersion, ">=", "2.0.0")) resourcePath = "/owners/"+sm_clientOrg+resourcePath;
		CandlepinTasks.deleteResourceUsingRESTfulAPI(sm_serverAdminUsername,sm_serverAdminPassword, sm_serverUrl,resourcePath);
		int sockets = 14;
		String poolid = null;
		String validity = null;
		List<String[]> listOfSectionNameValues = new ArrayList<String[]>();
		listOfSectionNameValues.add(new String[] { "rhsmcertd",
				"autoAttachInterval".toLowerCase(), "1440" });
		clienttasks.config(null, null, true, listOfSectionNameValues);
		Map<String, String> factsMap = new HashMap<String, String>();
		factsMap.put("virt.is_guest", String.valueOf(Boolean.FALSE));	// sockets based compliance now applies only to Physical systems; vcpu based compliance applies to Virtual systems
		factsMap.put("cpu.cpu_socket(s)", String.valueOf(sockets));
		clienttasks.createFactsFileWithOverridingValues(factsMap);
		clienttasks.facts(null, true, null, null, null);
		String consumerId = clienttasks.getCurrentConsumerId();
		ownerKey = CandlepinTasks.getOwnerKeyOfConsumerId(sm_serverAdminUsername, sm_serverAdminPassword, sm_serverUrl, consumerId);
		attributes.put("sockets", "2");
		attributes.put("arch", "ALL");
		attributes.put("type", "MKT");
		attributes.put("multi-entitlement", "yes");
		attributes.put("stacking_id", "726409");
		List<String> providedProducts = new ArrayList<String>();
		providedProducts.add("100000000000002");
		CandlepinTasks.createProductUsingRESTfulAPI(sm_serverAdminUsername, sm_serverAdminPassword, sm_serverUrl, sm_clientOrg, "Multi-Stackable for 100000000000002", "multi-stackable", 1, attributes, null);
		CandlepinTasks.createSubscriptionAndRefreshPoolsUsingRESTfulAPI(sm_serverAdminUsername, sm_serverAdminPassword, sm_serverUrl, sm_clientOrg, 20, -1*24*60/*1 day ago*/, 15*24*60/*15 days from now*/, getRandInt(), getRandInt(), "multi-stackable", providedProducts,null);
		JSONObject jobDetail = CandlepinTasks.refreshPoolsUsingRESTfulAPI(sm_serverAdminUsername, sm_serverAdminPassword, sm_serverUrl, ownerKey);
		jobDetail = CandlepinTasks.waitForJobDetailStateUsingRESTfulAPI(sm_serverAdminUsername,sm_serverAdminPassword,sm_serverUrl,jobDetail,"FINISHED", 5*1000, 1);
		attributes.put("sockets", "4");
		attributes.put("multi-entitlement", "no");
		CandlepinTasks.createProductUsingRESTfulAPI(sm_serverAdminUsername, sm_serverAdminPassword, sm_serverUrl, sm_clientOrg, "Stackable for 100000000000002", "stackable", 1, attributes, null);
		CandlepinTasks.createSubscriptionAndRefreshPoolsUsingRESTfulAPI(sm_serverAdminUsername, sm_serverAdminPassword, sm_serverUrl, sm_clientOrg, 20, -1*24*60/*1 day ago*/, 15*24*60/*15 days from now*/, getRandInt(), getRandInt(), "stackable", providedProducts,null);
		jobDetail = CandlepinTasks.refreshPoolsUsingRESTfulAPI(sm_serverAdminUsername, sm_serverAdminPassword, sm_serverUrl, ownerKey);
		jobDetail = CandlepinTasks.waitForJobDetailStateUsingRESTfulAPI(sm_serverAdminUsername,sm_serverAdminPassword,sm_serverUrl,jobDetail,"FINISHED", 5*1000, 1);

		clienttasks.unsubscribeFromAllOfTheCurrentlyConsumedProductSubscriptions();
		for (SubscriptionPool availList : clienttasks.getCurrentlyAllAvailableSubscriptionPools()) {
			if(availList.subscriptionName.equals("Multi-Stackable for 100000000000002")){
				poolid=availList.poolId;
				clienttasks.subscribe_(null, null, availList.poolId, null, null, "3", null, null, null, null, null, null);	
				validity=clienttasks.getFactValue(factname);
				Assert.assertEquals(validity.trim(), "partial");
			}else if( availList.subscriptionName.equals("Stackable for 100000000000002") ){
				clienttasks.subscribe_(null, null, availList.poolId, null, null,null, null, null, null, null, null, null);	
				validity=clienttasks.getFactValue(factname);
				Assert.assertEquals(validity.trim(), "partial");

			}
		}
		clienttasks.subscribe_(null, null, poolid, null, null, "2", null, null, null, null, null, null);	
		clienttasks.getCurrentlyConsumedProductSubscriptions();
		validity=clienttasks.getFactValue(factname);
		Assert.assertEquals(validity.trim(), "valid");
	}



	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(description = "verify OwnerInfo is displayed only for pools that are active right now, for all the stats", 
			groups = {"OwnerInfoForActivePools","blockedByBug-710141",}, enabled = true)
	public void OwnerInfoForActivePools() throws JSONException,Exception {
		DateFormat yyyy_MM_dd_DateFormat = new SimpleDateFormat("yyyy-MM-dd");
		clienttasks.register(sm_clientUsername, sm_clientPassword,
				sm_clientOrg, null, null, null, null, null, null, null,
				(List<String>)null,  (String)null, null, null, true, null, null, null, null);
		Calendar now = new GregorianCalendar();
		Calendar futureCalendar = new GregorianCalendar();
		futureCalendar.set(Calendar.HOUR_OF_DAY, 0); futureCalendar.set(Calendar.MINUTE, 0); futureCalendar.set(Calendar.SECOND, 0);	// avoid times in the middle of the day
		futureCalendar.add(Calendar.YEAR, 1);

		String futurceDate = yyyy_MM_dd_DateFormat.format(futureCalendar.getTime());
		List<SubscriptionPool> availOnDate = getAvailableFutureSubscriptionsOndate(futurceDate);
		if(availOnDate.size()==0) throw new SkipException(
				"Sufficient future pools are not available");

		JSONArray jsonPools = new JSONArray(CandlepinTasks.getResourceUsingRESTfulAPI(sm_serverAdminUsername,sm_serverAdminPassword, sm_serverUrl, "/owners/"+sm_clientOrg+"/pools"));
		Assert.assertTrue (jsonPools.length()>0,"Successfully got a positive number of /owners/"+sm_clientOrg+"/pools");
		for (int i = 0; i < jsonPools.length(); i++) {
			JSONObject jsonPool = (JSONObject) jsonPools.get(i);
			String subscriptionName = jsonPool.getString("productName");
			String startDate = jsonPool.getString("startDate");
			String endDate = jsonPool.getString("endDate");
			Calendar startCalendar = parseISO8601DateString(startDate,"GMT");	// "startDate":"2014-01-06T00:00:00.000+0000"
			Calendar endCalendar = parseISO8601DateString(endDate,"GMT");	// "endDate":"2015-01-06T00:00:00.000+0000"
			Assert.assertTrue(startCalendar.before(now),"Available pool '"+subscriptionName+"' startsDate='"+startDate+"' starts before now.");
			Assert.assertTrue(endCalendar.after(now),"Available pool '"+subscriptionName+"' endDate='"+endDate+"' ends after now.");
		}
		jsonPools = new JSONArray(CandlepinTasks.getResourceUsingRESTfulAPI(sm_serverAdminUsername,sm_serverAdminPassword, sm_serverUrl, "/owners/"+sm_clientOrg+"/pools?activeon="+futurceDate));
		Assert.assertTrue (jsonPools.length()>0,"Successfully got a positive number of /owners/"+sm_clientOrg+"/pools?activeon="+futurceDate);
		for (int i = 0; i < jsonPools.length(); i++) {
			JSONObject jsonPool = (JSONObject) jsonPools.get(i);
			String subscriptionName = jsonPool.getString("productName");
			String startDate = jsonPool.getString("startDate");
			String endDate = jsonPool.getString("endDate");
			Calendar startCalendar = parseISO8601DateString(startDate,"GMT");	// "startDate":"2014-01-06T00:00:00.000+0000"
			Calendar endCalendar = parseISO8601DateString(endDate,"GMT");	// "endDate":"2015-01-06T00:00:00.000+0000"
			Assert.assertTrue(startCalendar.before(futureCalendar),"Future available pool '"+subscriptionName+"' startsDate='"+startDate+"' starts before "+futurceDate+".");
			Assert.assertTrue(endCalendar.equals(futureCalendar)||endCalendar.after(futureCalendar),"Future available pool '"+subscriptionName+"' endDate='"+endDate+"' ends on or after "+futurceDate+".");
		}
	}
	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(description = "verify if refresh Pools w/ Auto-Create Owner Fails", 
			groups = { "EnableAndDisableCertV3","blockedByBug-919700"}, enabled = false)
	public void EnableAndDisableCertV3() throws JSONException,Exception {
		String version=null;
		servertasks.updateConfigFileParameter("candlepin.enable_cert_v3", "false");
		servertasks.restartTomcat();
		SubscriptionManagerCLITestScript.sleep( 1*60 * 1000);
		clienttasks.restart_rhsmcertd(null, null, null);
		clienttasks.register(sm_clientUsername, sm_clientPassword,
				sm_clientOrg, null, null, null, null, true, null, null,
				(String) null, null, null, null, true, null, null, null, null);
		for(EntitlementCert Cert: clienttasks.getCurrentEntitlementCerts()){
			version=Cert.version;
			if(version.equals("1.0")){
				Assert.assertEquals(version, "1.0");
			}else{
				servertasks.updateConfigFileParameter("candlepin.enable_cert_v3", "true");
				servertasks.restartTomcat();
				Assert.fail();
			}

		}
		clienttasks.unsubscribeFromAllOfTheCurrentlyConsumedProductSubscriptions();
		servertasks.updateConfigFileParameter("candlepin.enable_cert_v3", "true");
		servertasks.restartTomcat();
		clienttasks.restart_rhsmcertd(null, null, null);
		SubscriptionManagerCLITestScript.sleep( 1*60*1000);
		clienttasks.register(sm_clientUsername, sm_clientPassword,
				sm_clientOrg, null, null, null, null, true, null, null,
				(String) null, null, null, null, true, null, null, null, null);
		for(EntitlementCert Cert: clienttasks.getCurrentEntitlementCerts()){
			version=Cert.version;
			Assert.assertEquals(version, "3.2");
		}
	}


	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(description = "verify that refresh pools w/ auto_create_owner succeeds", 
			groups = { "RefreshPoolsWithAutoCreate","blockedByBug-720487"}, enabled = true)
	public void RefreshPoolsWithAutoCreate() throws JSONException,Exception {
		String org="newowner";
		CandlepinTasks.deleteResourceUsingRESTfulAPI(sm_serverAdminUsername,sm_serverAdminPassword, sm_serverUrl, "/owners/"+org); // in case org already exists
		JSONObject jsonOrg;
		jsonOrg = new JSONObject(CandlepinTasks.getResourceUsingRESTfulAPI(sm_serverAdminUsername,sm_serverAdminPassword, sm_serverUrl, "/owners/"+org));
		Assert.assertEquals(jsonOrg.getString("displayMessage"), "Organization with id newowner could not be found.");
		CandlepinTasks.putResourceUsingRESTfulAPI(sm_serverAdminUsername,sm_serverAdminPassword, sm_serverUrl,"/owners/"+org+"/subscriptions?auto_create_owner=true" );
		jsonOrg = new JSONObject(CandlepinTasks.getResourceUsingRESTfulAPI(sm_serverAdminUsername,sm_serverAdminPassword, sm_serverUrl, "/owners/"+org));
		Assert.assertNotNull(jsonOrg.get("created")); 
		jsonOrg= new JSONObject(CandlepinTasks.putResourceUsingRESTfulAPI(sm_serverAdminUsername,sm_serverAdminPassword, sm_serverUrl,"/owners/AnotherOwner/subscriptions?auto_create_owner=false" ));
		Assert.assertEquals(jsonOrg.getString("displayMessage"),"owner with key: AnotherOwner was not found.");
		CandlepinTasks.deleteResourceUsingRESTfulAPI(sm_serverAdminUsername,sm_serverAdminPassword, sm_serverUrl, "/owners/"+org);
	}

	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(description = "verify tracebacks occur running yum repolist after subscribing to a pool", 
			groups = { "VerifyipV4Facts","blockedByBug-874147"}, enabled = true)
	public void VerifyipV4Facts() throws JSONException,Exception {
		Boolean pattern=false;
		Boolean Flag=false;
		clienttasks.register(sm_clientUsername, sm_clientPassword,
				sm_clientOrg, null, null, null, null, null, null, null,
				(String) null, null, null, null, true, null, null, null, null);
		String result=clienttasks.facts(true, null, null, null, null).getStdout();
		Pattern p = Pattern.compile(result);
		Matcher matcher = p.matcher("Unknown");
		while (matcher.find()) {
			pattern = matcher.find();
		}
		Assert.assertEquals(pattern, Flag);
	}

	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(description = "verify tracebacks occur running yum repolist after subscribing to a pool", 
			groups = { "VerifyRepoFileExistance","blockedByBug-886604","blockedByBug-919700"}, enabled = true)
	public void VerifyRepoFileExistance() throws JSONException,Exception {
		List<String[]> listOfSectionNameValues = new ArrayList<String[]>();
		listOfSectionNameValues.add(new String[] { "rhsm","manage_repos", "1" });
		clienttasks.config(null, null, true, listOfSectionNameValues);
		clienttasks.register(sm_clientUsername, sm_clientPassword,
				sm_clientOrg, null, null, null, null, true, null, null,
				(String) null, null, null, null, true, null, null, null, null);
		List<YumRepo> originalRepos =clienttasks.getCurrentlySubscribedYumRepos();
		Assert.assertFalse(originalRepos.isEmpty());
		listOfSectionNameValues = new ArrayList<String[]>();
		listOfSectionNameValues.add(new String[] { "rhsm","manage_repos", "0" });
		clienttasks.config(null, null, true, listOfSectionNameValues);
		clienttasks.getYumRepolist("all");	// needed to trigger subscription-manager yum plugin
		originalRepos =clienttasks.getCurrentlySubscribedYumRepos();
		Assert.assertTrue(originalRepos.isEmpty());

	}


	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(description = "verify tracebacks occur running yum repolist after subscribing to a pool", 
			groups = { "AddingVirtualPoolToActivationKey","blockedByBug-755677"}, enabled = true)
	public void AddingVirtualPoolToActivationKey() throws JSONException,Exception {
		Integer addQuantity=1;

		String consumerId = clienttasks.getCurrentConsumerId(clienttasks.register(sm_clientUsername, sm_clientPassword,sm_clientOrg, null, null, null, null, null, null, null,(String) null, null, null, null, true, false, null, null, null));

		ownerKey = CandlepinTasks.getOwnerKeyOfConsumerId(sm_serverAdminUsername, sm_serverAdminPassword, sm_serverUrl, consumerId);
		String Productname,productId;
		List<String> providedProductIds = new ArrayList<String>();
		Productname = "virt-only-product to be added to activation key";
		productId = "virt-only-test-product";
		String poolId=null;
		Map<String,String> attributes = new HashMap<String,String>();
		attributes.put("version", "1.0");
		attributes.put("variant", "server");
		attributes.put("arch", "ALL");
		attributes.put("warning_period", "30");
		attributes.put("type", "MKT");
		attributes.put("type", "SVC");
		attributes.put("virt_limit", "unlimited");
		CandlepinTasks.deleteSubscriptionsAndRefreshPoolsUsingRESTfulAPI(sm_serverAdminUsername, sm_serverAdminPassword, sm_serverUrl, sm_clientOrg, productId);
		String resourcePath = "/products/"+productId;
		if (SubscriptionManagerTasks.isVersion(servertasks.statusVersion, ">=", "2.0.0")) resourcePath = "/owners/"+sm_clientOrg+resourcePath;
		CandlepinTasks.deleteResourceUsingRESTfulAPI(sm_serverAdminUsername, sm_serverAdminPassword, sm_serverUrl, resourcePath);

		CandlepinTasks.createProductUsingRESTfulAPI(sm_serverAdminUsername, sm_serverAdminPassword, sm_serverUrl, sm_clientOrg, Productname, productId, 1, attributes, null);
		CandlepinTasks.createSubscriptionAndRefreshPoolsUsingRESTfulAPI(sm_serverAdminUsername, sm_serverAdminPassword, sm_serverUrl, ownerKey, 20, -1*24*60/*1 day ago*/, 15*24*60/*15 days from now*/, getRandInt(), getRandInt(), productId, providedProductIds,null);
		String name = String.format("%s_%s-ActivationKey%s", sm_clientUsername,
				sm_clientOrg, System.currentTimeMillis());
		Map<String, String> mapActivationKeyRequest = new HashMap<String, String>();
		mapActivationKeyRequest.put("name", name);
		JSONObject jsonActivationKeyRequest = new JSONObject(
				mapActivationKeyRequest);
		JSONObject jsonActivationKey = new JSONObject(
				CandlepinTasks.postResourceUsingRESTfulAPI(sm_clientUsername,
						sm_clientPassword, sm_serverUrl, "/owners/"
								+ sm_clientOrg + "/activation_keys",
								jsonActivationKeyRequest.toString()));

		clienttasks.unsubscribe(true, (BigInteger) null, null, null, null);
		for (SubscriptionPool availList : clienttasks.getCurrentlyAllAvailableSubscriptionPools()) {
			if(availList.subscriptionName.equals(Productname)){
				poolId=availList.poolId;

			}
		}
		new JSONObject(CandlepinTasks.postResourceUsingRESTfulAPI(sm_clientUsername, sm_clientPassword, sm_serverUrl, "/activation_keys/" + jsonActivationKey.getString("id") + "/pools/" +poolId+(addQuantity==null?"":"?quantity="+addQuantity), null));

		clienttasks.register(null, null, sm_clientOrg, null, null, null, null, null, null, null, name, null, null, null, true, false, null, null, null);
		List <ProductSubscription> consumedProductSubscriptions = clienttasks.getCurrentlyConsumedProductSubscriptions();
		Assert.assertTrue(consumedProductSubscriptions.size()==1 && consumedProductSubscriptions.get(0).poolId.equals(poolId),"Registering with an activationKey named '"+name+"' should grant a single entitlement from subscription pool id '"+poolId+"'.");

		new JSONObject(CandlepinTasks.deleteResourceUsingRESTfulAPI(sm_clientUsername,
				sm_clientPassword, sm_serverUrl,"/activation_keys/"+name));
	}



	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(description = "verify tracebacks occur running yum repolist after subscribing to a pool", 
			groups = { "YumReposListAfterSubscription","blockedByBug-696786","blockedByBug-919700" }, enabled = true)
	public void YumReposListAfterSubscription() throws JSONException,Exception {
		Boolean pattern=false;
		Boolean Flag=false;
		String yum_cmd="yum repolist enabled --disableplugin=rhnplugin";
		String result=client.runCommandAndWait(yum_cmd).getStdout();
		clienttasks.register(sm_clientUsername, sm_clientPassword,sm_clientOrg, null, null, null, null, true, null, null,
				(String) null, null, null, null, true, null, null, null, null);
		result=client.runCommandAndWait(yum_cmd).getStdout();
		Pattern p = Pattern.compile(result);
		Matcher matcher = p.matcher("Traceback (most recent call last):");
		while (matcher.find()) {
			pattern = matcher.find();

		}
		Assert.assertEquals(Flag, pattern);
	}


	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@ImplementsNitrateTest(caseId=50235)
	@Test(	description = "verify rhsm log for Update With No Installed Products", 
	groups = {"UpdateWithNoInstalledProducts","blockedByBug-746241" },
	enabled = true)
	public void UpdateWithNoInstalledProducts() throws JSONException,Exception {
		client.runCommandAndWait("rm -f "+clienttasks.rhsmLogFile);	// remove it because it occasionally gets backed up to rhsm.log.1 in the midst of a pair of calls to RemoteFileTasks.markFile(...) and RemoteFileTasks.getTailFromMarkedFile(...)
		clienttasks.register(sm_clientUsername, sm_clientPassword,
				sm_clientOrg, null, null, null, null, null, null, null,
				(String) null, null, null, null, true, false, null, null, null);

		moveProductCertFiles("*.pem");
		String LogMarker = System.currentTimeMillis()+" Testing ***************************************************************";
		RemoteFileTasks.markFile(client, clienttasks.rhsmLogFile, LogMarker);
		String InstalledProducts=clienttasks.listInstalledProducts().getStdout();
		clienttasks.run_rhsmcertd_worker(null);		
		restoreProductCerts();
		Assert.assertEquals(InstalledProducts.trim(), "No installed products to list");
		String tailFromMarkedFile = RemoteFileTasks.getTailFromMarkedFile(client, clienttasks.rhsmLogFile, LogMarker, null);
		Assert.assertFalse(doesStringContainMatches(tailFromMarkedFile, "Error"),"'Error' messages in rhsm.log");	// "Error while updating certificates" should NOT be in the rhsm.log
		Assert.assertTrue(doesStringContainMatches(tailFromMarkedFile, "Installed product IDs: \\[\\]"), "'Installed product IDs:' list is empty in rhsm.log");
		Assert.assertTrue(doesStringContainMatches(tailFromMarkedFile, "certs updated:"),"'certs updated:' in rhsm.log");
	}





	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(description = "verify Facts Update For Deleted Consumer", 
			groups = { "FactsUpdateForDeletedConsumer","blockedByBug-798788" }, enabled = true)
	@ImplementsNitrateTest(caseId = 148216)
	public void FactsUpdateForDeletedConsumer() throws JSONException,Exception {

		clienttasks.register(sm_clientUsername, sm_clientPassword,
				sm_clientOrg, null, null, null, null, null, null, null,
				(String) null, null, null, null, true, null, null, null, null);
		clienttasks.autoheal(null, null, true, null, null, null);
		String consumerId = clienttasks.getCurrentConsumerId();
		CandlepinTasks.deleteResourceUsingRESTfulAPI(sm_serverAdminUsername,
				sm_serverAdminPassword, sm_serverUrl, "/consumers/"
						+ consumerId);
		String result=clienttasks.facts_(null, true, null, null, null).getStderr();
		String ExpectedMsg="Consumer "+consumerId+" has been deleted";
		if (!clienttasks.workaroundForBug876764(sm_serverType))
			ExpectedMsg = "Unit "+consumerId+" has been deleted";
		Assert.assertEquals(result.trim(), ExpectedMsg);
	}
	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(description = "verify if you can register using consumer id of a deleted owner", 
			groups = { "RegisterWithConsumeridOfDeletedOwner" }, enabled = true)
	@ImplementsNitrateTest(caseId = 148216)
	public void RegisterWithConsumeridOfDeletedOwner() throws JSONException,Exception {
		String orgname="testOwner1";
		servertasks.createOwnerUsingCPC(orgname);
		clienttasks.register_(sm_serverAdminUsername, sm_serverAdminPassword,
				orgname, null, null, null, null, null, null, null,
				(String) null, null, null, null, true, null, null, null, null);
		String consumerId=clienttasks.getCurrentConsumerId();
		CandlepinTasks.deleteResourceUsingRESTfulAPI(sm_serverAdminUsername,sm_serverAdminPassword, sm_serverUrl,"/owners/" + orgname);
		clienttasks.clean_(null, null, null);
		SSHCommandResult result=clienttasks.register_(sm_serverAdminUsername, sm_serverAdminPassword, orgname, null, null, null, consumerId, null, null, null,(String)null, null, null, null, null, null, null, null, null);
		String expected="Consumer "+consumerId+" has been deleted";
		if (!clienttasks.workaroundForBug876764(sm_serverType)) expected = "Unit "+consumerId+" has been deleted";
		Assert.assertEquals(result.getStderr().trim(), expected);
	}



	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(description = "verify if register to a deleted owner", 
			groups = { "RegisterToDeletedOwner" }, enabled = true)
	@ImplementsNitrateTest(caseId = 148216)
	public void RegisterToDeletedOwner() throws JSONException,Exception {
		String orgname="testOwner1";
		servertasks.createOwnerUsingCPC(orgname);
		CandlepinTasks.deleteResourceUsingRESTfulAPI(sm_serverAdminUsername,sm_serverAdminPassword, sm_serverUrl,"/owners/" + orgname);
		SSHCommandResult result=clienttasks.register_(sm_serverAdminUsername, sm_serverAdminPassword,orgname, null, null, null, null, null, null, null,(String) null, null, null, null, true, null, null, null, null);
		String expected="Organization "+orgname+" does not exist.";
		Assert.assertEquals(result.getStderr().trim(), expected);
	}


	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(description = "verify if Repos List is empty for FutureSubscription", 
			groups = {"EmptyReposListForFutureSubscription","blockedByBug-958775" }, enabled = true)
	public void EmptyReposListForFutureSubscription() throws JSONException,
	Exception {
		clienttasks.register(sm_clientUsername, sm_clientPassword,
				sm_clientOrg, null, null, null, null, null, null, null,
				(String) null, null, null, null, true, false, null, null, null);
		DateFormat yyyy_MM_dd_DateFormat = new SimpleDateFormat("yyyy-MM-dd");
		Calendar now = new GregorianCalendar();
		Calendar nextYear = new GregorianCalendar();
		nextYear.add(Calendar.YEAR, 1);	nextYear.add(Calendar.DATE, -7);	// one week less than one year
		String onDateToTest = yyyy_MM_dd_DateFormat.format(nextYear.getTime());
		List<String> subscriptionPoolIds = new ArrayList<String>();
		for (SubscriptionPool subscription : clienttasks.getAvailableFutureSubscriptionsOndate(onDateToTest)) {
			if (!CandlepinTasks.isPoolAModifier(sm_clientUsername, sm_clientPassword, subscription.poolId, sm_serverUrl)) {
				subscriptionPoolIds.add(subscription.poolId);
			}
		}
		clienttasks.subscribe(null, null, subscriptionPoolIds, null, null, null, null, null, null, null, null, null);

		// determine if both active and inactive entitlements are being consumed
		boolean activeProductSubscriptionsConsumed = false;
		boolean inactiveProductSubscriptionsConsumed = false;
		List<ProductSubscription> currentlyConsumedProductSubscriptions = clienttasks.getCurrentlyConsumedProductSubscriptions();
		for (ProductSubscription subscriptions : currentlyConsumedProductSubscriptions) {
			if(subscriptions.isActive) activeProductSubscriptionsConsumed = true;
			if(!subscriptions.isActive) inactiveProductSubscriptionsConsumed = true;
		}
		if (!activeProductSubscriptionsConsumed || !inactiveProductSubscriptionsConsumed) {
			throw new SkipException("This test assumes that both current and future subscriptions are available on '"+onDateToTest+"' which is determined by the subscriptions loaded on the candlepin server."); 
		}
		// the following loop will remove all currently active entitlements
		Assert.assertTrue(!clienttasks.getCurrentlySubscribedRepos().isEmpty(), "There should be entitled repos since we are consuming current entitlements (indicated by Active:True) are attached.");
		for (ProductSubscription subscriptions : currentlyConsumedProductSubscriptions) {
			if(subscriptions.isActive) {
				clienttasks.unsubscribe(null, subscriptions.serialNumber, null, null, null);
			}
		}
		Assert.assertTrue(!clienttasks.getCurrentlyConsumedProductSubscriptions().isEmpty(), "We should still be consuming future entitlements (indicated by Active:False).");
		Assert.assertTrue(clienttasks.getCurrentlySubscribedRepos().isEmpty(), "There should not be any entitled repos despite the future attached entitlements (indicated by Active:False).");
	}



	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(description = "verify if auto-subscribe and activation-key are mutually exclusive", groups = {
			"VerifyAutoSubscribeAndActivationkeyTogether",
	"blockedByBug-869729" }, enabled = true)
	public void VerifyAutoSubscribeAndActivationkeyTogether()
			throws JSONException, Exception {
		clienttasks.register(sm_clientUsername, sm_clientPassword,
				sm_clientOrg, null, null, null, null, null, null, null,
				(String) null, null, null, null, true, null, null, null, null);
		String name = String.format("%s_%s-ActivationKey%s", sm_clientUsername,
				sm_clientOrg, System.currentTimeMillis());
		Map<String, String> mapActivationKeyRequest = new HashMap<String, String>();
		mapActivationKeyRequest.put("name", name);
		JSONObject jsonActivationKeyRequest = new JSONObject(
				mapActivationKeyRequest);
		JSONObject jsonActivationKey = new JSONObject(
				CandlepinTasks.postResourceUsingRESTfulAPI(sm_clientUsername,
						sm_clientPassword, sm_serverUrl, "/owners/"
								+ sm_clientOrg + "/activation_keys",
								jsonActivationKeyRequest.toString()));
		SSHCommandResult result = clienttasks.register_(null, null,
				sm_clientOrg, null, null, null, null, true, null, null,
				jsonActivationKey.get("name").toString(), null, null, null,
				true, null, null, null, null);
		String expected_msg = "Error: Activation keys cannot be used with --auto-attach.";
		if (clienttasks.isPackageVersion("subscription-manager",">=","1.13.9-1")) {	// post commit a695ef2d1da882c5f851fde90a24f957b70a63ad
			Assert.assertEquals(result.getStderr().trim(), expected_msg,"stderr");	
		} else {
			Assert.assertEquals(result.getStdout().trim(), expected_msg,"stdout");	
		}
	}

	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	/*
	 * @Test( description=
	 * "verify if entitlement certs are regenerated if certs are manually removed"
	 * , groups={"VerifyDuplicateContentsInReposList"}, enabled=true)
	 * 
	 * @ImplementsNitrateTest(caseId=50229) public void
	 * VerifyDuplicateContentsInReposList() throws JSONException, Exception {
	 * clienttasks
	 * .register(sm_clientUsername,sm_clientPassword,sm_clientOrg,null
	 * ,null,null,null,null,null,null,(String)null,null, null, true,null,null,
	 * null, null); List<String[]> listOfSectionNameValues = new
	 * ArrayList<String[]>(); listOfSectionNameValues.add(new
	 * String[]{"rhsmcertd","healFrequency".toLowerCase(), "1440"});
	 * clienttasks.config(null,null,true,listOfSectionNameValues);
	 * clienttasks.unsubscribe(true, (BigInteger)null, null, null, null);
	 * for(SubscriptionPool pool
	 * :clienttasks.getCurrentlyAllAvailableSubscriptionPools()){ List<String>
	 * providedProducts =
	 * CandlepinTasks.getPoolProvidedProductIds(sm_clientUsername,
	 * sm_clientPassword, sm_serverUrl, pool.poolId);
	 * System.out.println(providedProducts + "  providedProducts"); } }
	 */

	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(description = "verify if entitlement certs are regenerated if certs are manually removed", groups = { "VerifyRegenrateEntitlementCert" }, enabled = true)
	@ImplementsNitrateTest(caseId = 64181)
	public void VerifyRegenrateEntitlementCert() throws JSONException,
	Exception {
		String poolId = null;
		int Certfrequeny = 1;
		clienttasks.register(sm_clientUsername, sm_clientPassword,
				sm_clientOrg, null, null, null, null, null, null, null,
				(String) null, null, null, null, true, null, null, null, null);
		List<String[]> listOfSectionNameValues = new ArrayList<String[]>();
		listOfSectionNameValues.add(new String[] { "rhsmcertd",
				"autoAttachInterval".toLowerCase(), "1440" });
		clienttasks.config(null, null, true, listOfSectionNameValues);
		clienttasks.unsubscribe(true, (BigInteger) null, null, null, null);
		for (SubscriptionPool availList : clienttasks
				.getCurrentlyAvailableSubscriptionPools()) {
			poolId = availList.poolId;

		}
		clienttasks.subscribe_(null, null, poolId, null, null, null, null,
				null, null, null, null, null);
		client.runCommandAndWait("rm -rf " + clienttasks.entitlementCertDir
				+ "/*.pem");
		clienttasks.restart_rhsmcertd(Certfrequeny, null, null);
		SubscriptionManagerCLITestScript.sleep(Certfrequeny * 60 * 1000);
		List<File> Cert = clienttasks.getCurrentEntitlementCertFiles();
		Assert.assertNotNull(Cert.size());
	}

	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(description = "verify if entitlement certs are downloaded if subscribed using bogus poolid", groups = { "VerifySubscribingTobogusPoolID" }, enabled = true)
	@ImplementsNitrateTest(caseId = 50223)
	public void VerifySubscribingTobogusPoolID() throws JSONException,
	Exception {
		String poolId = null;
		clienttasks.register(sm_clientUsername, sm_clientPassword,
				sm_clientOrg, null, null, null, null, null, null, null,
				(String) null, null, null, null, true, null, null, null, null);
		List<String[]> listOfSectionNameValues = new ArrayList<String[]>();
		listOfSectionNameValues.add(new String[] { "rhsmcertd",
				"autoAttachInterval".toLowerCase(), "1440" });
		clienttasks.config(null, null, true, listOfSectionNameValues);
		clienttasks.unsubscribe(true, (BigInteger) null, null, null, null);
		for (SubscriptionPool availList : clienttasks
				.getCurrentlyAvailableSubscriptionPools()) {
			poolId = availList.poolId;

		}
		String pool = randomizeCaseOfCharactersInString(poolId);
		clienttasks.subscribe_(null, null, pool, null, null, null, null, null,
				null, null, null, null);
		List<File> Cert = clienttasks.getCurrentEntitlementCertFiles();
		Assert.assertEquals(Cert.size(), 0);
	}

	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(description = "Verify Functionality Access After Unregister", groups = { "VerifyFunctionalityAccessAfterUnregister" }, enabled = true)
	@ImplementsNitrateTest(caseId = 50215)
	public void VerifyFunctionalityAccessAfterUnregister()
			throws JSONException, Exception {
		clienttasks
		.register(sm_clientUsername, sm_clientPassword, sm_clientOrg);
		String availList = clienttasks.listAllAvailableSubscriptionPools()
				.getStdout();
		Assert.assertNotNull(availList);
		clienttasks.unregister(null, null, null);
		SSHCommandResult listResult = clienttasks.list_(true, true, null, null, null, null, null,
				null, null, null, null, null, null);
		String expected = "This system is not yet registered. Try 'subscription-manager register --help' for more information.";
		if (clienttasks.isPackageVersion("subscription-manager",">=","1.13.8-1")) {	// post commit df95529a5edd0be456b3528b74344be283c4d258
			Assert.assertEquals(listResult.getStderr().trim(), expected,"stderr");
		} else {
			Assert.assertEquals(listResult.getStdout().trim(), expected,"stdout");
		}
		ConsumerCert consumercert = clienttasks.getCurrentConsumerCert();
		Assert.assertNull(consumercert);
	}

	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(description = "Verify only One Cert is downloaded Per One Subscription", groups = {"VerifyOneCertPerOneSubscription"}, enabled = true)
	@ImplementsNitrateTest(caseId = 50215)
	public void VerifyOneCertPerOneSubscription() {
		int expected = 0;
		clienttasks.register(sm_clientUsername, sm_clientPassword,
				sm_clientOrg, null, null, null, null, null, null, null,
				(String) null, null, null, null, true, false, null, null, null);
		/* unnecessary
		clienttasks.restart_rhsmcertd(null, null, null);
		clienttasks.deleteFactsFileWithOverridingValues();
		clienttasks.unsubscribe(true, (BigInteger) null, null, null, null);
		 */
		/* takes too much time to execute; choose a random subset of available pools
		for (SubscriptionPool subscriptionpool : clienttasks
				.getCurrentlyAvailableSubscriptionPools()) {
		 */
		for (SubscriptionPool subscriptionpool : getRandomSubsetOfList(clienttasks.getCurrentlyAvailableSubscriptionPools(),5)) {
			clienttasks.subscribe_(null, null, subscriptionpool.poolId, null,
					null, null, null, null, null, null, null, null);
			expected = expected + 1;
			List<File> Cert = clienttasks.getCurrentEntitlementCertFiles();
			Assert.assertEquals(Cert.size(), expected, "Total number of local entitlement certs after subscribing to '"+expected+"' different pools.");
		}
	}

	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(description = "verify content set associated with product", groups = { "VerifyUnsubscribingCertV3","blockedByBug-895447"}, enabled = false)
	@ImplementsNitrateTest(caseId = 50215)
	public void VerifyUnsubscribingCertV3() throws JSONException, Exception {

		clienttasks.register(sm_clientUsername, sm_clientPassword,
				sm_clientOrg, null, null, null, null, null, null, null,
				(String) null, null, null, null, true, null, null, null, null);
		File expectCertFile = new File(System.getProperty("automation.dir",
				null) + "/expiredcerts/CertV3.pem");
		RemoteFileTasks.putFile(client.getConnection(),
				expectCertFile.toString(), "/root/", "0755");
		clienttasks.importCertificate_("/root/CertV3.pem");
		String expected = "0 subscriptions removed at the server."+"\n"+"1 local certificate has been deleted.";
		String result = clienttasks.unsubscribe(true, (BigInteger) null, null,
				null, null).getStdout();
		Assert.assertEquals(result.trim(), expected);
	}

	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(description = "verify  rhsmcertd is logging update failed (255)", groups = {
			"VerifyRHSMCertdLogging", "blockedByBug-708512" }, enabled = true)
	public void VerifyRHSMCertdLogging() throws JSONException, Exception {
		int autoAttachInterval = 1;
		
		clienttasks.unregister(null, null, null);
		
		clienttasks.restart_rhsmcertd(autoAttachInterval, null, false);
		clienttasks.waitForRegexInRhsmcertdLog("Update failed", 1);	// Thu Feb 13 02:01:16 2014 [WARN] (Cert Check) Update failed (255), retry will occur on next run.
		
	}

	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(description = "verify content set associated with product", groups = { "VerifycertsAfterUnsubscribeAndunregister" }, enabled = true)
	@ImplementsNitrateTest(caseId = 50215)
	public void VerifyProductCertsAfterUnsubscribeAndunregister()
			throws JSONException, Exception {

		clienttasks.register(sm_clientUsername, sm_clientPassword,
				sm_clientOrg, null, null, null, null, null, null, null,
				(String) null, null, null, null, true, false, null, null, null);
		clienttasks.subscribe_(true, null, (String) null, null, null, null,
				null, null, null, null, null, null);
		List<File> ProductCerts = clienttasks.getCurrentProductCertFiles();
		Assert.assertFalse(ProductCerts.isEmpty());
		clienttasks.unsubscribe(true, (BigInteger) null, null, null, null);
		List<File> certs = clienttasks.getCurrentEntitlementCertFiles();
		Assert.assertTrue(certs.isEmpty());
		ProductCerts.clear();
		ProductCerts = clienttasks.getCurrentProductCertFiles();
		Assert.assertFalse(ProductCerts.isEmpty());
		clienttasks.unregister(null, null, null);
		ConsumerCert consumerCerts = clienttasks.getCurrentConsumerCert();
		Assert.assertNull(consumerCerts);
	}

	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(	description = "verify reregister with invalid consumerid",
			groups = { "VerifyRegisterUsingInavlidConsumerId" },
			enabled = true)
	@ImplementsNitrateTest(caseId = 61716)
	public void VerifyregisterUsingInavlidConsumerId() throws JSONException,
	Exception {
		clienttasks.register(sm_clientUsername, sm_clientPassword,
				sm_clientOrg, null, null, null, null, null, null, null,
				(String) null, null, null, null, true, null, null, null, null);
		String consumerId = clienttasks.getCurrentConsumerId();
		String invalidconsumerId = randomGenerator.nextInt() + consumerId;
		System.out.println(invalidconsumerId + "  " + consumerId);
		SSHCommandResult result = clienttasks.register_(sm_clientUsername,
				sm_clientPassword, sm_clientOrg, null, null, null,
				invalidconsumerId, null, null, null, (String) null, null, null,
				null, true, null, null, null, null);
		String expectedStdout = "The system with UUID " + consumerId + " has been unregistered";
		String expectedStderr = "Consumer with id " + invalidconsumerId + " could not be found.";
		if (clienttasks.isPackageVersion("subscription-manager",">=","1.15.9-2")) expectedStdout += String.format("\n"+"Registering to: %s:%s%s",clienttasks.getConfParameter("hostname"),clienttasks.getConfParameter("port"),clienttasks.getConfParameter("prefix"));	// subscription-manager commit d5014cda1c234d36943383b69898f2a651202b89 RHEL7.2 commit 968e6a407054c96291a4e64166c4840529772fff Bug 985157 - [RFE] Specify which username to enter when registering with subscription-manager
		Assert.assertEquals(result.getStdout().trim(), expectedStdout, "stdout");
		Assert.assertEquals(result.getStderr().trim(), expectedStderr, "stderr");
	}

	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(description = "verify if corrupt identity cert displays a trace back for list command", groups = {
			"VerifyCorruptIdentityCert", "blockedByBug-607162" }, enabled = true)
	public void VerifycorruptIdentityCert() throws JSONException, Exception {
		clienttasks.register(sm_clientUsername, sm_clientPassword,
				sm_clientOrg, null, null, null, null, null, null, null,
				(String) null, null, null, null, true, null, null, null, null);
		client.runCommandAndWait("cp /etc/pki/consumer/cert.pem /etc/pki/consumer/cert.pem.save");
		RemoteFileTasks.runCommandAndAssert(
				client,
				"openssl x509 -noout -text -in "
						+ clienttasks.consumerCertFile()
						+ " > /tmp/stdout; mv /tmp/stdout -f "
						+ clienttasks.consumerCertFile(), 0);
		SSHCommandResult result = clienttasks.list_(null, true, null, null, null, null,
				null, null, null, null, null, null, null);
		if (clienttasks.isPackageVersion("subscription-manager",">=","1.13.8-1")) {	// post commit df95529a5edd0be456b3528b74344be283c4d258
			Assert.assertEquals(result.getStderr().trim(), clienttasks.msg_ConsumerNotRegistered,"stderr");
		} else {
			Assert.assertEquals(result.getStdout().trim(), clienttasks.msg_ConsumerNotRegistered,"stdout");
		}
		client.runCommandAndWait("mv -f /etc/pki/consumer/cert.pem.save /etc/pki/consumer/cert.pem");

	}

	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(description = "subscription-manager facts --update changes update date after facts update", groups = {
			"VerifyUpdateConsumerFacts", "blockedByBug-700821" }, enabled = true)
	public void VerifyupdateConsumerFacts() throws JSONException, Exception {

		clienttasks.register(sm_clientUsername, sm_clientPassword,
				sm_clientOrg, null, null, null, null, null, null, null,
				(String) null, null, null, null, true, null, null, null, null);
		String consumerid = clienttasks.getCurrentConsumerId();
		JSONObject jsonConsumer = new JSONObject(
				CandlepinTasks.getResourceUsingRESTfulAPI(
						sm_serverAdminUsername, sm_serverAdminPassword,
						sm_serverUrl, "/consumers/" + consumerid));
		String createdDateBeforeUpdate = jsonConsumer.getString("created");
		String UpdateDateBeforeUpdate = jsonConsumer.getString("updated");
		clienttasks.facts(null, true, null, null, null).getStderr();
		jsonConsumer = new JSONObject(
				CandlepinTasks.getResourceUsingRESTfulAPI(
						sm_serverAdminUsername, sm_serverAdminPassword,
						sm_serverUrl, "/consumers/" + consumerid));
		String createdDateAfterUpdate = jsonConsumer.getString("created");
		String UpdateDateAfterUpdate = jsonConsumer.getString("updated");
		Assert.assertEquals(createdDateBeforeUpdate, createdDateAfterUpdate,
				"no changed in date value after facts update");
		Assert.assertNoMatch(UpdateDateBeforeUpdate, UpdateDateAfterUpdate,
				"updated date has been changed after facts update");
	}

	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(	description = "verify healing of installed products without taking future subscriptions into consideration",
			groups = {"VerifyHealingForFutureSubscription","blockedByBug-907638"},
			enabled = true)
	public void VerifyHealingForFutureSubscription() throws JSONException,
	Exception {
		String productId =null;

		clienttasks.register(sm_clientUsername, sm_clientPassword,
				sm_clientOrg, null, null, null, null, null, null, null,
				(String) null, null, null, null, true, null, null, null, null);
		clienttasks.run_rhsmcertd_worker(true);
		List<InstalledProduct> currentlyInstalledProducts = clienttasks.getCurrentlyInstalledProducts();
		clienttasks.unsubscribeFromTheCurrentlyConsumedSerialsCollectively();
		clienttasks.autoheal(null, null, true, null, null, null); // disabling autoheal
		Calendar now = new GregorianCalendar();
		DateFormat yyyy_MM_dd_DateFormat = new SimpleDateFormat("yyyy-MM-dd");
		now.add(Calendar.YEAR, 1);
		now.add(Calendar.DATE, 1);
		String onDateToTest = yyyy_MM_dd_DateFormat.format(now.getTime());
		List<SubscriptionPool> availOnDate = getAvailableFutureSubscriptionsOndate(onDateToTest);
		if(availOnDate.size()==0) throw new SkipException("Sufficient future pools are not available");
		SubscriptionPool futureAvailableSubscriptionPool = null;
		for (SubscriptionPool subscriptionPool : getRandomList(availOnDate)) {
			// skip future temporary pools to avoid:
			// 201510051235:34.087 - FINE: ssh root@jsefler-7.usersys.redhat.com subscription-manager subscribe --pool=8a9087905019c618015019c77f500880 (com.redhat.qe.tools.SSHCommandRunner.run)
			// 201510051235:37.684 - FINE: Stdout: Pool is restricted when it is temporary and begins in the future:  '8a9087905019c618015019c77f500880'
			if (subscriptionPool.subscriptionType.endsWith("(Temporary)")) continue;
			List<String> providedProductIdsOfSubscriptionPool = CandlepinTasks.getPoolProvidedProductIds(sm_clientUsername, sm_clientPassword, sm_serverUrl, subscriptionPool.poolId);
			for (String providedProductId : providedProductIdsOfSubscriptionPool) {
				if (InstalledProduct.findFirstInstanceWithMatchingFieldFromList("productId", providedProductId, currentlyInstalledProducts)!=null) {
					futureAvailableSubscriptionPool = subscriptionPool;
				}
			}
		}
		if (futureAvailableSubscriptionPool==null) throw new SkipException("Could not find a viable future subscription that provides for an installed product to test on '"+onDateToTest+"'.");
		clienttasks.subscribe(null, null, futureAvailableSubscriptionPool.poolId, null, null, null, null, null, null, null, null, null);
		ProductSubscription futureConsumedProductSubscription = ProductSubscription.findFirstInstanceWithMatchingFieldFromList("poolId", futureAvailableSubscriptionPool.poolId, clienttasks.getCurrentlyConsumedProductSubscriptions());
		String expectedFutureConsumedProductSubscriptionStatusDetails = "Subscription has not begun";
		Assert.assertTrue(futureConsumedProductSubscription.statusDetails.contains(expectedFutureConsumedProductSubscriptionStatusDetails), "The status details of the future consumed subscription states '"+expectedFutureConsumedProductSubscriptionStatusDetails+"'.");
		List<String> providedProductIdsOfFutureConsumedProductSubscription = CandlepinTasks.getPoolProvidedProductIds(sm_clientUsername, sm_clientPassword, sm_serverUrl, futureAvailableSubscriptionPool.poolId);
		List<InstalledProduct> installedProducts = clienttasks.getCurrentlyInstalledProducts();
		for (String providedProductId : providedProductIdsOfFutureConsumedProductSubscription) {
			InstalledProduct installedProduct = InstalledProduct.findFirstInstanceWithMatchingFieldFromList("productId", providedProductId, installedProducts);
			if (installedProduct!=null) {
				Assert.assertEquals(installedProduct.status,"Future Subscription","Status of an installed product provided by a future consumed subscription.");
				productId = installedProduct.productId;
			}
		}
		if (productId==null) throw new SkipException("None of the provided products from consumed future subscription '"+futureConsumedProductSubscription+"' are installed for testing.");
		clienttasks.autoheal(null, true, null, null, null, null); // enabling autoheal
		clienttasks.run_rhsmcertd_worker(true);
		boolean assertedFutureSubscriptionIsNowSubscribed = false;
		InstalledProduct installedProductAfterAutoHealing = InstalledProduct.findFirstInstanceWithMatchingFieldFromList("productId", productId, clienttasks.getCurrentlyInstalledProducts());
		List<String> installedProductArches = new ArrayList<String>(Arrays.asList(installedProductAfterAutoHealing.arch.trim().split(" *, *")));	// Note: the arch can be a comma separated list of values
		if (installedProductArches.contains("x86")) {installedProductArches.addAll(Arrays.asList("i386","i486","i586","i686"));}	// Note: x86 is a general alias to cover all 32-bit intel microprocessors, expand the x86 alias
		if (installedProductArches.contains(clienttasks.arch) || installedProductArches.contains("ALL")) {
			Assert.assertEquals(installedProductAfterAutoHealing.status.trim(),
					"Subscribed", "Previously installed product '"+installedProductAfterAutoHealing.productName+"' covered by a Future Subscription should now be covered by a current subscription after auto-healing.");
			assertedFutureSubscriptionIsNowSubscribed = true;
		} else {
			Assert.assertEquals(installedProductAfterAutoHealing.status.trim(),
					"Future Subscription", "Mismatching arch installed product '"+installedProductAfterAutoHealing.productName+"' (arch='"+installedProductAfterAutoHealing.arch+"') covered by a Future Subscription should remain unchanged after auto-healing.");			
		}
		
		Assert.assertTrue(assertedFutureSubscriptionIsNowSubscribed,"Verified at least one previously installed product covered by a Future Subscription is now covered by a current subscription after auto-healing.");
	}

	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(	description = "Verify unsubscribe from multiple invalid serial numbers",
			groups = {"blockedByBug-1268491", "UnsubscribeFromInvalidMultipleEntitlements" },
			enabled = true)
	@ImplementsNitrateTest(caseId = 50230)
	public void UnsubscribeFromInvalidMultipleEntitlements()
			throws JSONException, Exception {
		List<BigInteger> serialnums = new ArrayList<BigInteger>();
		clienttasks.register(sm_clientUsername, sm_clientPassword,
				sm_clientOrg, null, null, null, null, null, null, null,
				(String) null, null, null, null, true, null, null, null, null);
		clienttasks.unsubscribe_(true, (BigInteger) null, null, null, null);
//		for (SubscriptionPool pool : clienttasks.getCurrentlyAvailableSubscriptionPools()) {
//			clienttasks.subscribe_(null, null, pool.poolId, null, null, null, null, null, null, null, null, null);
//		}
// too slow, this is faster subscribeToTheCurrentlyAvailableSubscriptionPoolsCollectively();
		clienttasks.subscribeToTheCurrentlyAvailableSubscriptionPoolsCollectively();
		if(clienttasks.getCurrentlyConsumedProductSubscriptions().isEmpty())throw new SkipException(
				"Sufficient pools are not available");
		for (ProductSubscription consumed : clienttasks.getCurrentlyConsumedProductSubscriptions()) {
			serialnums.add(consumed.serialNumber);
		}

		int i = randomGenerator.nextInt(serialnums.size());
		int j = randomGenerator.nextInt(serialnums.size());
		if (i == j) {
			j = randomGenerator.nextInt(serialnums.size());

		}
		BigInteger serialOne = serialnums.get(i);
		BigInteger serialTwo = serialnums.get(j);
		String result = unsubscribeFromMultipleEntitlementsUsingSerialNumber(
				serialOne.multiply(serialTwo), serialTwo.multiply(serialOne))
				.getStdout();
		String expected = "Serial numbers unsuccessfully removed at the server:" + "\n"
				+ "   " + serialOne.multiply(serialTwo)
				+ " is not a valid value for serial" + "\n" + "   "
				+ serialTwo.multiply(serialOne)
				+ " is not a valid value for serial";
		Assert.assertEquals(result.trim(), expected);
	}

	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(description = "Verify unsubscribe from multiple subscriptions", groups = {
			"UnsubscribeFromMultipleEntitlementsTest", "blockedByBug-867766","blockedByBug-906550" }, enabled = true)
	@ImplementsNitrateTest(caseId = 50230)
	public void UnsubscribeFromMultipleEntitlements() throws JSONException,
	Exception {
		int count=0;
		List<BigInteger> serialnums = new ArrayList<BigInteger>();
		clienttasks.register(sm_clientUsername, sm_clientPassword,
				sm_clientOrg, null, null, null, null, null, null, null,
				(String) null, null, null, null, true, null, null, null, null);
		clienttasks.unsubscribe(true, (BigInteger) null, null, null, null);
		for (SubscriptionPool pool : clienttasks.getCurrentlyAvailableSubscriptionPools()) {
			if((count<=2)){
				if (CandlepinTasks.isPoolAModifier(sm_clientUsername, sm_clientPassword, pool.poolId, sm_serverUrl)) continue;	// skip modifier pools
				count++;
				clienttasks.subscribe(null, null, pool.poolId, null, null, null, null, null, null, null, null, null);
			}

		}

		for (ProductSubscription consumed : clienttasks
				.getCurrentlyConsumedProductSubscriptions()) {
			serialnums.add(consumed.serialNumber);
		}
		/* does not guarantee i != j
		int i = randomGenerator.nextInt(serialnums.size());
		int j = randomGenerator.nextInt(serialnums.size());
		if (i == j) {
			j = randomGenerator.nextInt(serialnums.size());

		}
		 */
		BigInteger serialOne = serialnums.get(randomGenerator.nextInt(serialnums.size()));	// serialnums.get(i);
		serialnums.remove(serialOne);
		BigInteger serialTwo = serialnums.get(randomGenerator.nextInt(serialnums.size()));	// serialnums.get(j);
		String result = unsubscribeFromMultipleEntitlementsUsingSerialNumber(
				serialOne, serialTwo).getStdout();

		String expected = "Serial numbers successfully removed at the server:" + "\n" + "   "
				+ serialOne + "\n" + "   " + serialTwo+ "\n" 
				+"2 local certificates have been deleted.";
		Assert.assertEquals(result.trim(), expected);
	}

	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(description = "verify content set associated with product", groups = { "VerifyRegisterWithConsumerIdForDifferentUser" }, enabled = true)
	@ImplementsNitrateTest(caseId = 61710)
	public void VerifyRegisterWithConsumerIdForDifferentUser()
			throws JSONException, Exception {
		if (sm_client2Username==null) throw new SkipException("This test requires valid credentials for a second user.");
		clienttasks.register(sm_client2Username, sm_client2Password,
				sm_client2Org, null, null, null, null, null, null, null,
				(String) null, null, null, null, true, null, null, null, null);
		String consumerid = clienttasks.getCurrentConsumerId();
		String result = clienttasks.register_(sm_clientUsername,
				sm_clientPassword, sm_clientOrg, null, null, null, consumerid,
				null, null, null, (String) null, null, null, null, true, null,
				null, null, null).getStderr();
		System.out.println("result  " + result);
		Assert.assertNotNull(result);
	}

	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(description = "verify content set associated with product", groups = { "VerifyFactsListByOverridingValues" }, enabled = true)
	@ImplementsNitrateTest(caseId = 56389)
	public void VerifyFactsListByOverridingValues() throws JSONException,
	Exception {

		clienttasks.register(sm_clientUsername, sm_clientPassword,
				sm_clientOrg, null, null, null, null, null, null, null,
				(String) null, null, null, null, true, null, null, null, null);
		String listBeforeUpdate = clienttasks.facts(true, null, null, null,
				null).getStdout();
		Map<String, String> factsMap = new HashMap<String, String>();
		Integer sockets = 4;
		factsMap.put("cpu.cpu_socket(s)", String.valueOf(sockets));
		factsMap.put("uname.machine", "i386");
		clienttasks.createFactsFileWithOverridingValues(factsMap);
		clienttasks.facts(null, true, null, null, null);
		String listAfterUpdate = clienttasks.facts(true, null, null, null,
				null).getStdout();
		Assert.assertNoMatch(listAfterUpdate, listBeforeUpdate);
		/* unnecessary, AfterGroups method deleteFactsFileWithOverridingValues() "VerifyFactsListByOverridingValues" will do it
		clienttasks.deleteFactsFileWithOverridingValues();
		clienttasks.facts(null, true, null, null, null);
		 */
	}

	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(description = "verify content set associated with product", groups = { "VerifyFactsListWithOutrageousValues" }, enabled = true)
	@ImplementsNitrateTest(caseId = 56897)
	public void VerifyFactsListWithOutrageousValues() throws JSONException,	Exception {

		clienttasks.register(sm_clientUsername, sm_clientPassword,
				sm_clientOrg, null, null, null, null, null, null, null,
				(String) null, null, null, null, true, null, null, null, null);
		String listBeforeUpdate = clienttasks.facts(true, null, null, null,
				null).getStdout();

		client.runCommandAndWait("echo '{fuzzing :testing}' >>/var/lib/rhsm/facts/facts.json");
		clienttasks.facts(null, true, null, null, null);
		String listAfterUpdate = clienttasks.facts(true, null, null, null,
				null).getStdout();
		Assert.assertFalse(listAfterUpdate.contentEquals("fuzzing"));
		Assert.assertEquals(listAfterUpdate, listBeforeUpdate);
		client.runCommandAndWait("cp /var/lib/rhsm/facts/facts.json /var/lib/rhsm/facts/facts.json.save");
		client.runCommandAndWait("sed /'uname.machine: x86_64'/d /var/lib/rhsm/facts/facts.json");
		clienttasks.facts(null, true, null, null, null);
		listAfterUpdate = clienttasks.facts(true, null, null, null, null)
				.getStdout();
		Assert.assertFalse(listAfterUpdate.contentEquals("uname.machine: x86_64"));
		client.runCommandAndWait("mv -f /var/lib/rhsm/facts/facts.json.save /var/lib/rhsm/facts/facts.json");
		//Assert.assertEquals(listAfterUpdate, listBeforeUpdate);

	}

	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(description = "verify content set associated with product", groups = { "Verifycontentsetassociatedwithproduct" }, enabled = true)
	@ImplementsNitrateTest(caseId = 61115)
	public void Verifycontentsetassociatedwithproduct() throws JSONException,
	Exception {
		clienttasks.unregister(null, null, null);
		clienttasks.register(sm_clientUsername, sm_clientPassword,
				sm_clientOrg, null, null, null, null, null, null, null,
				(String) null, null, null, null, true, null, null, null, null);
		clienttasks.unsubscribe(true, (BigInteger)null, null, null, null);
		List<SubscriptionPool> pools = clienttasks
				.getCurrentlyAvailableSubscriptionPools();
		clienttasks.subscribeToSubscriptionPool(pools.get(randomGenerator
				.nextInt(pools.size())));
		List<File> certs = clienttasks.getCurrentEntitlementCertFiles();
		RemoteFileTasks.runCommandAndAssert(
				client,
				"openssl x509 -noout -text -in "
						+ certs.get(randomGenerator.nextInt(certs.size()))
						+ " > /tmp/stdout; mv /tmp/stdout -f "
						+ certs.get(randomGenerator.nextInt(certs.size())), 0);
		String consumed = clienttasks.list_(null, null, true, null, null, null,
				null, null, null, null, null, null, null).getStderr();
		Assert.assertEquals(consumed.trim(), "Error loading certificate");

	}

	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(description = "verify if rhsmcertd process refresh the identity certificate after every restart", groups = {
			"VerifyrhsmcertdRefreshIdentityCert", "blockedByBug-827034","blockedByBug-923159",
	"blockedByBug-827035" }, enabled = false)
	// TODO disabling this test for two reasons:
	//  1. it is dangerous to change the system dates
	//  2. the network service seems to stop when the date changes breaking the ability to ssh into the system
	public void VerifyrhsmcertdRefreshIdentityCert() throws JSONException,
	Exception {


		clienttasks.register(sm_clientUsername, sm_clientPassword,
				sm_clientOrg, null, null, null, null, null, null, null,
				(String) null, null, null, null, true, null, null, null, null);
		DateFormat yyyy_MM_dd_DateFormat = new SimpleDateFormat("yyyy-MM-dd");
		log.info(clienttasks.getCurrentConsumerCert().validityNotBefore.toString() +"   "+ clienttasks.getCurrentConsumerCert().validityNotAfter.toString() +" cert validity before regeneration");
		Calendar StartTimeBeforeRHSM = clienttasks.getCurrentConsumerCert().validityNotBefore;
		Calendar EndTimeBeforeRHSM = clienttasks.getCurrentConsumerCert().validityNotAfter;
		String EndDateBeforeRHSM = yyyy_MM_dd_DateFormat.format(clienttasks.getCurrentConsumerCert().validityNotAfter.getTime());
		String StartDateBeforeRHSM=yyyy_MM_dd_DateFormat.format(clienttasks.getCurrentConsumerCert().validityNotBefore.getTime());
		String existingCertdate = client.runCommandAndWait("ls -lart /etc/pki/consumer/cert.pem | cut -d ' ' -f6,7,8")
				.getStdout();
		String StartDate=setDate(sm_serverHostname, sm_sshUser, sm_sshKeyPrivate,
				sm_sshkeyPassphrase, "date -s '15 year 9 month' +'%F'");
		log.info("Changed the date of candlepin"
				+ client.runCommandAndWait("hostname"));
		setDate(sm_clientHostname, sm_sshUser, sm_sshKeyPrivate,
				sm_sshkeyPassphrase, "date -s '15 year 9 month' +'%F'");
		clienttasks.restart_rhsmcertd(null, null, null);
		SubscriptionManagerCLITestScript.sleep(2* 60 * 1000);
		log.info(clienttasks.getCurrentConsumerCert().validityNotBefore.toString() +"   "+ clienttasks.getCurrentConsumerCert().validityNotAfter.toString() +" cert validity After regeneration");
		Calendar StartTimeAfterRHSM = clienttasks.getCurrentConsumerCert().validityNotBefore;
		Calendar EndTimeAfterRHSM = clienttasks.getCurrentConsumerCert().validityNotAfter;
		String EndDateAfterRHSM = yyyy_MM_dd_DateFormat.format(clienttasks.getCurrentConsumerCert().validityNotAfter.getTime());
		String StartDateAfterRHSM=yyyy_MM_dd_DateFormat.format(clienttasks.getCurrentConsumerCert().validityNotBefore.getTime());
		String updatedCertdate = client.runCommandAndWait("ls -lart /etc/pki/consumer/cert.pem | cut -d ' ' -f6,7,8").getStderr();
		String EndDate=setDate(sm_serverHostname, sm_sshUser, sm_sshKeyPrivate,
				sm_sshkeyPassphrase, "date -s '15 year ago 9 month ago' +'%F'");
		log.info("Changed the date of candlepin"
				+ client.runCommandAndWait("hostname"));
		setDate(sm_clientHostname, sm_sshUser, sm_sshKeyPrivate,
				sm_sshkeyPassphrase, "date -s '15 year ago 9 month ago' +'%F'");
		System.out.println();
		Assert.assertEquals(StartDateAfterRHSM, StartDate);
		Assert.assertEquals(EndDateAfterRHSM, EndDate);
		Assert.assertNotSame(StartTimeBeforeRHSM.getTime(),
				StartTimeAfterRHSM.getTime());
		Assert.assertNotSame(EndTimeBeforeRHSM.getTime(),
				EndTimeAfterRHSM.getTime());
		Assert.assertNotSame(existingCertdate, updatedCertdate);

	}



	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(	description = "subscription-manager unsubscribe --all on expired subscriptions removes certs from entitlement folder",
			groups = {"VerifyUnsubscribeAllForExpiredSubscription", "blockedByBug-852630","blockedByBug-906550" },
			enabled = true)
	public void VerifyUnsubscribeAllForExpiredSubscription()
			throws JSONException, Exception {

		clienttasks.register(sm_clientUsername, sm_clientPassword,
				sm_clientOrg, null, null, null, null, true, null, null,
				(String) null, null, null, null, true, false, null, null, null);

		File expectCertFile = new File(System.getProperty("automation.dir",
				null) + "/expiredcerts/Expiredcert.pem");
		RemoteFileTasks.putFile(client.getConnection(),
				expectCertFile.toString(), "/root/", "0755");
		clienttasks.importCertificate("/root/Expiredcert.pem");
		List<ProductSubscription> consumedProductSubscriptions = clienttasks.getCurrentlyConsumedProductSubscriptions();
		List<ProductSubscription> activeProductSubscriptions = ProductSubscription.findAllInstancesWithMatchingFieldFromList("isActive", Boolean.TRUE, consumedProductSubscriptions);
		Set<BigInteger> activeProductSubscriptionSerials = new HashSet<BigInteger>();
		for (ProductSubscription activeProductSubscription : activeProductSubscriptions) activeProductSubscriptionSerials.add(activeProductSubscription.serialNumber);
		List<ProductSubscription> expiredProductSubscriptions = ProductSubscription.findAllInstancesWithMatchingFieldFromList("isActive", Boolean.FALSE, consumedProductSubscriptions);
		Assert.assertEquals(expiredProductSubscriptions.size(), 1, "Found one expired entitlement (indicated by Active:False) among the list of consumed subscriptions.");
		SSHCommandResult result = clienttasks.unsubscribe(true,(BigInteger) null, null, null, null);
		String expected = String.format("%d subscriptions removed at the server.\n%d local certificates have been deleted.",activeProductSubscriptionSerials.size(),activeProductSubscriptionSerials.size()+expiredProductSubscriptions.size());
		if (activeProductSubscriptionSerials.size()+expiredProductSubscriptions.size()==1) expected = expected.replace("local certificates have been", "local certificate has been");
		Assert.assertEquals(result.getStdout().trim(), expected);

	}

	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(description = "Verify One empty certificate file in /etc/rhsm/ca causes registration failure", groups = {
			"VerifyEmptyCertCauseRegistrationFailure_Test",
	"blockedByBug-806958" }, enabled = true)
	public void VerifyEmptyCertCauseRegistrationFailure_Test()
			throws JSONException, Exception {
		clienttasks.unregister(null, null, null);
		String FilePath = myEmptyCaCertFile;
		String command = "touch " + FilePath;
		client.runCommandAndWait(command);
		SSHCommandResult result = clienttasks.register_(sm_clientUsername,
				sm_clientPassword, sm_clientOrg, null, null, null, null, null,
				null, null, (String) null, null, null, null, null, null, null,
				null, null);
		String Expected = "Bad CA certificate: " + FilePath;
		if (clienttasks.isPackageVersion("subscription-manager",">=","1.13.9-1")) {	// post commit a695ef2d1da882c5f851fde90a24f957b70a63ad
			Assert.assertEquals(result.getStderr().trim(), Expected,"stderr");	
		} else {
			Assert.assertEquals(result.getStdout().trim(), Expected,"stdout");	
		}
	}




	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(	description = "Verify facts update with incorrect proxy url produces traceback.",
			groups = {"VerifyFactsWithIncorrectProxy_Test", "blockedByBug-744504" },
			enabled = true)
	public void VerifyFactsWithIncorrectProxy_Test() throws JSONException,
	Exception {
		clienttasks.register(sm_clientUsername, sm_clientPassword,
				sm_clientOrg, null, null, null, null, null, null, null,
				(String) null, null, null, null, true, null, null, null, null);
		String basicauthproxyUrl = String.format("%s:%s", "testmachine.com",
				sm_basicauthproxyPort);
		basicauthproxyUrl = basicauthproxyUrl.replaceAll(":$", "");
		SSHCommandResult factsResult = clienttasks.facts_(null, true, basicauthproxyUrl, null,null);
		String Expect = clienttasks.msg_NetworkErrorUnableToConnect;
		Expect = "Error updating system data on the server, see /var/log/rhsm/rhsm.log for more details.";	// jsefler 6/17/2014 - the expected error message changed to this value.  Could not find a bugzilla/commit to blame this change.
		Assert.assertEquals(factsResult.getStdout()+factsResult.getStderr().trim(), Expect);
	}

	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(description = "Verify Subscription Manager Leaves Broken Yum Repos After Unregister", groups = {
			"ReposListAfterUnregisterTest", "blockedByBug-674652" }, enabled = true)
	public void VerifyRepoAfterUnregisterTest() throws JSONException,
	Exception {
		clienttasks.register(sm_clientUsername, sm_clientPassword,
				sm_clientOrg, null, null, null, null, true, null, null,
				(String) null, null, null, null, true, null, null, null, null);
		clienttasks.unsubscribe(true, (BigInteger)null, null, null, null);
		clienttasks.subscribeToTheCurrentlyAvailableSubscriptionPoolsCollectively();
		List<YumRepo> repos = clienttasks.getCurrentlySubscribedYumRepos();
		Assert.assertFalse(repos.isEmpty());
		clienttasks.unregister(null, null, null);
		List<YumRepo> repo = clienttasks.getCurrentlySubscribedYumRepos();
		Assert.assertTrue(repo.isEmpty());
	}

	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(description = "Verify if stacking entitlements reports as distinct entries in cli list --installed", groups = {
			"VerifyDistinct", "blockedByBug-733327" }, enabled = true)
	public void VerifyDistinctStackingEntires() throws Exception {


		String poolId = null;
		clienttasks.register(sm_clientUsername, sm_clientPassword,sm_clientOrg, null, null, null, null, null, null, null,(String) null, null, null, null, true, null, null, null, null);
		clienttasks.autoheal(null, null, true, null, null, null);
		int sockets = 4;
		Map<String, String> factsMap = new HashMap<String, String>();
		factsMap.put("cpu.cpu_socket(s)", String.valueOf(sockets));
		factsMap.put("virt.is_guest", String.valueOf(Boolean.FALSE));
		clienttasks.createFactsFileWithOverridingValues(factsMap);
		clienttasks.facts(null, true, null, null, null);

		boolean testResourceFound=false;
		for (SubscriptionPool pool : clienttasks.getCurrentlyAvailableSubscriptionPools()) {
			if(pool.productId.equals("awesomeos-x86_64")){
				clienttasks.subscribe(null, null, pool.poolId, null, null,"2", null, null, null, null, null, null);
				poolId = pool.poolId;
				testResourceFound=true; break;
			}
		}
		Assert.assertTrue(testResourceFound, "Found a pool corresponding to productId awesomeos-x86_64 needed for this test.");

		testResourceFound=false;
		for (InstalledProduct installed : clienttasks.getCurrentlyInstalledProducts()) {
			if(installed.productId.equals("100000000000002")){
				Assert.assertEquals(installed.status, "Partially Subscribed");
				testResourceFound=true; break;
			}
		}
		Assert.assertTrue(testResourceFound, "Found the installed productId 100000000000002 provided by subscription productId awesomeos-x86_64 needed for this test.");

		clienttasks.subscribe(null, null, poolId, null, null, "2",null, null, null, null, null, null);
		testResourceFound=false;
		for (InstalledProduct installedProduct : clienttasks.getCurrentlyInstalledProducts()) {
			if(installedProduct.productId.equals("100000000000002")){
				Assert.assertEquals(installedProduct.status, "Subscribed");
				testResourceFound=true; break;
			}
		}
		Assert.assertTrue(testResourceFound, "Found the installed productId 100000000000002 provided by subscription productId awesomeos-x86_64 needed for this test.");


	}


	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(description = "Verify that Product with UUID '%s' cannot be deleted while subscriptions exist.",
			groups = {"DeleteProductTest", "blockedByBug-684941" },
			enabled = true)
	public void VerifyDeletionOfSubscribedProduct_Test() throws JSONException, Exception {
		clienttasks.register(sm_clientUsername, sm_clientPassword,
				sm_clientOrg, null, null, null, null, null, null, null,
				(List<String>) null, null, null, null, true, null, null, null, null);
		String consumerid=clienttasks.getCurrentConsumerId();
		clienttasks.subscribe_(true, null, null, (String) null, null, null,
				null, null, null, null, null, null);
		if(clienttasks.getCurrentlyConsumedProductSubscriptions().isEmpty()){
			throw new SkipException("no installed products are installed");
		} else {
			for (InstalledProduct installed : clienttasks.getCurrentlyInstalledProducts()) {
				if (installed.status.equals("Subscribed")) {
					for (SubscriptionPool AvailSub : clienttasks.getCurrentlyAvailableSubscriptionPools()) {
						if (installed.productName.contains(AvailSub.subscriptionName)) {
							String resourcePath = "/products/" + AvailSub.productId;
							if (SubscriptionManagerTasks.isVersion(servertasks.statusVersion, ">=", "2.0.0")) resourcePath = "/owners/"+sm_clientOrg+resourcePath;
							JSONObject jsonConsumer = new JSONObject (CandlepinTasks.deleteResourceUsingRESTfulAPI(sm_serverAdminUsername,sm_serverAdminPassword, sm_serverUrl,resourcePath));
							//String result=server.runCommandAndWait("tail -5 /var/log/candlepin/candlepin.log | grep req").getStdout();
							String expectedDisplayMessage = "Product with UUID '"+ AvailSub.productId+ "' cannot be deleted while subscriptions exist.";
							if (SubscriptionManagerTasks.isVersion(servertasks.statusVersion, ">=", "2.0.0")) expectedDisplayMessage = "Product with ID '"+AvailSub.productId+"' cannot be deleted while subscriptions exist.";
							Assert.assertEquals(jsonConsumer.getString("displayMessage"), expectedDisplayMessage);
						}
					}
				}
			}
		}
	}

	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(description = "Verify Force Registration After Consumer is Deleted", groups = {
			"ForceRegAfterDEL", "blockedByBug-853876" }, enabled = true)
	public void VerifyForceRegistrationAfterConsumerDeletion_Test()
			throws JSONException, Exception {
		clienttasks.register(sm_clientUsername, sm_clientPassword,
				sm_clientOrg, null, null, null, null, null, null, null,
				(List<String>) null, null, null, null, true, null, null, null, null);
		String consumerId = clienttasks.getCurrentConsumerId();
		CandlepinTasks.deleteResourceUsingRESTfulAPI(sm_serverAdminUsername,
				sm_serverAdminPassword, sm_serverUrl, "/consumers/"
						+ consumerId);
		String result = clienttasks.register(sm_clientUsername,
				sm_clientPassword, sm_clientOrg, null, null, null, null, null,
				null, null, (List<String>) null, null, null, null, true, null,
				null, null, null).getStdout();

		Assert.assertContainsMatch(result.trim(),
				"The system has been registered with ID: [a-f,0-9,\\-]{36}");

	}

	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(description = "verify config Server port with blank or incorrect text produces traceback", groups = { "configBlankTest","blockedByBug-744654" }, enabled = true)
	// @ImplementsNitrateTest(caseId=)
	public void ConfigSetServerPortValueToBlank_Test() {

		List<String[]> listOfSectionNameValues = new ArrayList<String[]>();
		String section = "server";
		String name = "port";
		String newValue = clienttasks.getConfFileParameter(
				clienttasks.rhsmConfFile, section, name);
		listOfSectionNameValues.add(new String[] { section, name.toLowerCase(),
		"" });
		SSHCommandResult results = clienttasks.config(null, null, true,
				listOfSectionNameValues);
		String value = clienttasks.getConfFileParameter(
				clienttasks.rhsmConfFile, section, name);
		Assert.assertEquals("", results.getStdout().trim());
		listOfSectionNameValues.add(new String[] { section, name.toLowerCase(),
				newValue });
		clienttasks.config(null, null, true, listOfSectionNameValues);
		value = clienttasks.getConfFileParameter(clienttasks.rhsmConfFile,
				section, name);
		Assert.assertEquals(value, newValue);
	}

	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(description = "subscription-manager: register --name , setting consumer name to blank", groups = {
			"registerwithname", "blockedByBug-669395" }, enabled = true)
	public void registerWithNameBlankTest() throws JSONException, Exception {
		String name = "test";
		clienttasks.register(sm_clientUsername, sm_clientPassword,
				sm_clientOrg, null, null, name, null, null, null, null,
				(String) null, null, null, null, true, null, null, null, null);
		ConsumerCert consumerCert = clienttasks.getCurrentConsumerCert();
		Assert.assertEquals(consumerCert.name, name);
		name = "";
		SSHCommandResult result = clienttasks.register_(sm_clientUsername,
				sm_clientPassword, sm_clientOrg, null, null, name, null, null,
				null, null, (String) null, null, null, null, true, null, null,
				null, null);
		String expectedMsg = String
				.format("Error: system name can not be empty.");
		if (clienttasks.isPackageVersion("subscription-manager",">=","1.13.8-1")) {	// post commit df95529a5edd0be456b3528b74344be283c4d258
			Assert.assertEquals(result.getStderr().trim(), expectedMsg,"stderr");
			Assert.assertEquals(result.getExitCode(), new Integer(64));
		} else {
			Assert.assertEquals(result.getStdout().trim(), expectedMsg,"stdout");
			Assert.assertEquals(result.getExitCode(), new Integer(255));
		}
		consumerCert = clienttasks.getCurrentConsumerCert();
		Assert.assertNotNull(consumerCert.name);

	}

	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(description = "subscription-manager: register --consumerid  using a different user and valid consumerId", groups = {
			"reregister", "blockedByBug-627665" }, enabled = true)
	public void registerWithConsumerid_Test() throws JSONException, Exception {
		clienttasks.register(sm_clientUsername, sm_clientPassword,
				sm_clientOrg, null, null, null, null, null, null, null,
				(String) null, null, null, null, true, null, null, null, null);
		String consumerId = clienttasks.getCurrentConsumerId();
		clienttasks.unsubscribe(true, (BigInteger)null, null, null, null);
		List<SubscriptionPool> pools = clienttasks
				.getCurrentlyAvailableSubscriptionPools();
		if (pools.isEmpty())
			throw new SkipException(
					"Cannot randomly pick a pool for subscribing when there are no available pools for testing.");
		SubscriptionPool pool = pools
				.get(randomGenerator.nextInt(pools.size()));
		clienttasks.subscribeToSubscriptionPool(pool);
		List<ProductSubscription> consumedSubscriptionsBeforeregister = clienttasks
				.getCurrentlyConsumedProductSubscriptions();
		clienttasks.clean_(null, null, null);
		if (sm_client2Username==null) throw new SkipException("This test requires valid credentials for a second user.");
		clienttasks.register_(sm_client2Username, sm_client2Password,
				sm_client2Org, null, null, null, consumerId, null, null, null,
				(String) null, null, null, null, null, null, null, null, null);
		String consumerIdAfter = clienttasks.getCurrentConsumerId();
		Assert.assertEquals(consumerId, consumerIdAfter,
				"The consumer identity  has not changed after registering with consumerid.");
		List<ProductSubscription> consumedscriptionsAfterregister = clienttasks
				.getCurrentlyConsumedProductSubscriptions();
		Assert.assertTrue(
				consumedscriptionsAfterregister
				.containsAll(consumedSubscriptionsBeforeregister)
				&& consumedSubscriptionsBeforeregister.size() == consumedscriptionsAfterregister
				.size(),
				"The list of consumed products after reregistering is identical.");
	}

	/**
	 * @author skallesh
	 */
	@Test(description = "subscription-manager: service-level --org (without --list option)", groups = {
			"ServicelevelTest", "blockedByBug-826856" }, enabled = true)
	public void ServiceLevelWithOrgWithoutList_Test() {

		SSHCommandResult result;
		clienttasks.register(sm_clientUsername, sm_clientPassword,
				sm_clientOrg, null, null, null, null, null, null, null,
				(List<String>) null, null, null, null, true, null, null, null, null);
		result = clienttasks.service_level_(null, false, null, null,
				sm_clientUsername, sm_clientPassword, "MyOrg", null, null,
				null, null, null);
		if (clienttasks.isPackageVersion("subscription-manager",">=","1.13.9-1")) {	// post commit a695ef2d1da882c5f851fde90a24f957b70a63ad
			Assert.assertEquals(result.getStderr().trim(), "Error: --org is only supported with the --list option","stderr");	
		} else {
			Assert.assertEquals(result.getStdout().trim(), "Error: --org is only supported with the --list option","stdout");	
		}
	}

	/**
	 * @author skallesh
	 */
	@Test(description = "subscription-manager: facts --update (when registered)", groups = {
			"MyTestFacts", "blockedByBug-707525" }, enabled = true)
	public void FactsUpdateWhenregistered_Test() {

		clienttasks.register(sm_clientUsername, sm_clientPassword,
				sm_clientOrg, null, null, null, null, null, null, null,
				(List<String>) null, null, null, null, true, null, null, null, null);
		SSHCommandResult result = clienttasks.facts(null, true, null, null,
				null);
		Assert.assertEquals(result.getStdout().trim(),
				"Successfully updated the system facts.");
	}



	/**
	 * @author skallesh
	 */
	@Test(description = "subscription-manager: attempt register to with white space in the user name should fail", groups = {
			"registeredTests", "blockedByBug-719378" }, enabled = true)
	public void AttemptregisterWithWhiteSpacesInUsername_Test() {
		SSHCommandResult result = clienttasks.register_("user name",
				"password", sm_clientOrg, null, null, null, null, null, null,
				null, (String) null, null, null, null, true, null, null, null, null);
		Assert.assertEquals(
				result.getStderr().trim(),
				servertasks.invalidCredentialsMsg(),
				"The expected stdout result when attempting to register with a username containing whitespace.");
	}

	/**
	 * @author skallesh
	 * @throws JSONException
	 * @throws Exception
	 */
	@Test(description = "Auto-heal for partial subscription", groups = {
			"autohealPartial", "blockedByBug-746218","blockedByBug-907638","blockedByBug-907400"}, enabled = true)
	public void VerifyAutohealForPartialSubscription() throws Exception {
		Integer moreSockets = 0;
		List<String> productIds = new ArrayList<String>();
		List<String> poolId = new ArrayList<String>();
		Map<String, String> factsMap = new HashMap<String, String>();
		factsMap.put("virt.is_guest", Boolean.FALSE.toString());
		clienttasks.createFactsFileWithOverridingValues(factsMap);
		clienttasks.register(sm_clientUsername, sm_clientPassword,
				sm_clientOrg, null, null, null, null, true, null, null,
				(String) null, null, null, null, true, null, null, null, null);
		clienttasks.autoheal(null, null, true, null, null, null); 	// disable autoheal
		for (SubscriptionPool pool : clienttasks
				.getCurrentlyAvailableSubscriptionPools()) {

			if (CandlepinTasks.isPoolProductMultiEntitlement(sm_clientUsername, sm_clientPassword, sm_serverUrl, pool.poolId)) {
				String poolProductSocketsAttribute = CandlepinTasks
						.getPoolProductAttributeValue(sm_clientUsername,
								sm_clientPassword, sm_serverUrl, pool.poolId,
								"stacking_id");
				if ((!(poolProductSocketsAttribute == null))
						&& (poolProductSocketsAttribute.equals("1"))) {
					String SocketsCount = CandlepinTasks
							.getPoolProductAttributeValue(sm_clientUsername,
									sm_clientPassword, sm_serverUrl,
									pool.poolId, "sockets");
					poolId.add(pool.poolId);
					moreSockets += Integer.valueOf(SocketsCount);
					productIds.addAll(CandlepinTasks.getPoolProvidedProductIds(sm_clientUsername, sm_clientPassword, sm_serverUrl, pool.poolId));
				}
			}
		}
		if (moreSockets==0) throw new SkipException("Expected to find a sockets based multi-entitlement pool with stacking_id 1 for this test.");
		factsMap.put("cpu.cpu_socket(s)", String.valueOf((++moreSockets)+Integer.valueOf(clienttasks.sockets)));
		clienttasks.createFactsFileWithOverridingValues(factsMap);
		clienttasks.facts(null, true, null, null, null);

		for (InstalledProduct installedProduct : clienttasks
				.getCurrentlyInstalledProducts()) {
			if (productIds.contains(installedProduct.productId)) {
				System.out.println(installedProduct.productName);
				Assert.assertEquals(installedProduct.status,
						"Partially Subscribed");
			}
		}
		Assert.assertTrue(!productIds.isEmpty(),"Found installed products that are partially subscribed after adding "+moreSockets+" more cpu.cpu_socket(s).");
		clienttasks.autoheal(null, true, null, null, null, null); 	// enable autoheal
		clienttasks.run_rhsmcertd_worker(true);	// trigger autoheal
		for (InstalledProduct installedProduct : clienttasks
				.getCurrentlyInstalledProducts()) {
			for (String productId : productIds) {
				if (productId.equals(installedProduct.productId))
					Assert.assertEquals(installedProduct.status, "Subscribed", "Status of installed product '"+installedProduct.productName+"' after auto-healing.");
			}
		}

	}

	/**
	 * @author skallesh
	 * @throws JSONException
	 * @throws Exception
	 */
	@Test(description = "Auto-heal with SLA",	// TODO Add some more description; has same description as VerifyAutohealFailForSLA()
			groups = {"AutoHealWithSLA","blockedByBug-907638","blockedByBug-907400"}, enabled = true)
	public void VerifyAutohealWithSLA() throws JSONException, Exception {
		/* not necessary; will use clienttasks.run_rhsmcertd_worker(true) to invoke an immediate autoheal
		Integer autoAttachInterval = 2;
		 */
		clienttasks.register(sm_clientUsername, sm_clientPassword,
				sm_clientOrg, null, null, null, null, null, null, null,
				(String) null, null, null, null, true, false, null, null, null);
		List<String> availableServiceLevelData = clienttasks
				.getCurrentlyAvailableServiceLevels();

		String randomServiceLevel=null;
		for (String randomAvailableServiceLevel : getRandomSubsetOfList(availableServiceLevelData,availableServiceLevelData.size())) {
			randomServiceLevel = randomAvailableServiceLevel;
			clienttasks.subscribe_(true, randomAvailableServiceLevel, (String) null, null, null, null, null, null, null, null, null, null);
			if (!clienttasks.getCurrentEntitlementCertFiles().isEmpty()) break;
		}
		if (clienttasks.getCurrentEntitlementCertFiles().isEmpty()) throw new SkipException("Could not find an available SLA that could be used to auto subscribe coverage for an installed product.");
		String currentServiceLevel = clienttasks.getCurrentServiceLevel();
		Assert.assertEquals(randomServiceLevel, currentServiceLevel,"The current service level should report the same value used during autosubscribe.");
		clienttasks.unsubscribeFromAllOfTheCurrentlyConsumedProductSubscriptions();


		clienttasks.autoheal(null, true, null, null, null, null);
		clienttasks.run_rhsmcertd_worker(true);
		List<ProductSubscription> productSubscriptions = clienttasks.getCurrentlyConsumedProductSubscriptions();
		Assert.assertTrue(!productSubscriptions.isEmpty(), "Autoheal with serviceLevel '"+currentServiceLevel+"' has granted this system some entitlement coverage.");
		for (ProductSubscription productSubscription : productSubscriptions) {
			//TODO Fix the exempt service level logic in this loop after implementation of Bug 1066088 - [RFE] expose an option to the servicelevels api to return exempt service levels
			if (!sm_exemptServiceLevelsInUpperCase.contains("Exempt SLA".toUpperCase())) sm_exemptServiceLevelsInUpperCase.add("Exempt SLA".toUpperCase()); //WORKAROUND for bug 1066088
			if (sm_exemptServiceLevelsInUpperCase.contains(productSubscription.serviceLevel.toUpperCase())) {
				Assert.assertTrue(sm_exemptServiceLevelsInUpperCase.contains(productSubscription.serviceLevel.toUpperCase()), "Autohealed subscription '"+productSubscription.productName+"' has been granted with an exempt service level '"+productSubscription.serviceLevel+"'.");		
			} else if ((productSubscription.serviceLevel==null||productSubscription.serviceLevel.isEmpty()) && clienttasks.isVersion(servertasks.statusVersion, ">", "2.0.2-1")) {	// commit 9cefb6e23baefcc4ee2e14423f205edd37eecf22	// Bug 1223560 - Service levels on an activation key prevent custom products from attaching at registration if auto-attach enabled (reported by Christine Fouant)
				log.info("Due to Bug 1223560, Autoheal with serviceLevel '"+currentServiceLevel+"' granted this system coverage from subscription '"+productSubscription.productName+"' which actually has no service level.");
			} else {
				Assert.assertEquals(productSubscription.serviceLevel, currentServiceLevel, "Autohealed subscription '"+productSubscription.productName+"' has been granted with the expected service level.");
			}
		}
	}

	/**
	 * @author skallesh
	 * @throws Exception
	 */
	@Test(description = "verfying Auto-heal when auto-heal parameter is turned off",
			groups = { "AutohealTurnedOff","blockedByBug-726411" }, enabled = true)
	public void AutohealTurnedOff() throws Exception {
		/* not necessary; will use clienttasks.run_rhsmcertd_worker(true) to invoke an immediate autoheal
		Integer healFrequency = 2;
		 */
		clienttasks.register(sm_clientUsername, sm_clientPassword,
				sm_clientOrg, null, null, null, null, null, null, null,
				(String) null, null, null, null, true, null, null, null, null);
		/* autoheal disablement has now been simplified by calling the autoheal module...
		String consumerId = clienttasks.getCurrentConsumerId();
		JSONObject jsonConsumer = CandlepinTasks.setAutohealForConsumer(
				sm_clientUsername, sm_clientPassword, sm_serverUrl, consumerId,
				false);
		Assert.assertFalse(
				jsonConsumer.getBoolean("autoheal"),
				"A consumer's autoheal attribute value can be toggled off (expected value=false).");
		 */
		clienttasks.autoheal(null, null, true, null, null, null);
		/* this takes too long, lets call run_rhsmcertd_worker(true) instead
		clienttasks.restart_rhsmcertd(null, healFrequency, null);
		SubscriptionManagerCLITestScript.sleep(healFrequency * 60 * 1000);
		 */
		clienttasks.run_rhsmcertd_worker(true);
		List<EntitlementCert> certs = clienttasks.getCurrentEntitlementCerts();
		Assert.assertTrue((certs.isEmpty()), "When autoheal has been disabled, no entitlements should be granted after the rhsmcertd worker has run.");
	}

	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */

	@Test(description = "Verify if Subscription manager displays incorrect status for partially subscribed subscription", groups = {
			"VerifyStatusForPartialSubscription", "blockedByBug-743710" }, enabled = true)
	@ImplementsNitrateTest(caseId = 119327)
	public void VerifyStatusForPartialSubscription() throws JSONException,
	Exception {

		String Flag = "false";
		clienttasks.register(sm_clientUsername, sm_clientPassword,
				sm_clientOrg, null, null, null, null, null, null, null,
				(String) null, null, null, null, true, false, null, null, null);

		Map<String, String> factsMap = new HashMap<String, String>();
		factsMap.put("virt.is_guest", String.valueOf(Boolean.FALSE));
		clienttasks.createFactsFileWithOverridingValues(factsMap);
		clienttasks.facts(null, true, null, null, null);
		for (SubscriptionPool SubscriptionPool : clienttasks
				.getCurrentlyAllAvailableSubscriptionPools()) {
			if (!CandlepinTasks.isPoolProductMultiEntitlement(sm_clientUsername, sm_clientPassword, sm_serverUrl, SubscriptionPool.poolId)) {
				String poolProductSocketsAttribute = CandlepinTasks
						.getPoolProductAttributeValue(sm_clientUsername,
								sm_clientPassword, sm_serverUrl,
								SubscriptionPool.poolId, "sockets");
				if ((!(poolProductSocketsAttribute == null))
						&& (poolProductSocketsAttribute.equals("2"))) {
					clienttasks.subscribeToSubscriptionPool_(SubscriptionPool);
					Flag = "true";
				}
			}
		}
		Assert.assertTrue(Boolean.valueOf(Flag),"Found and subscribed to non-multi-entitlement 2 socket subscription pool(s) for this test.");
		Integer moreSockets = 4;
		factsMap.put("cpu.cpu_socket(s)", String.valueOf(moreSockets+Integer.valueOf(clienttasks.sockets)));
		clienttasks.createFactsFileWithOverridingValues(factsMap);
		clienttasks.facts(null, true, null, null, null);
		Flag = "false";
		for (InstalledProduct product : clienttasks
				.getCurrentlyInstalledProducts()) {
			//			if (product.status.equals("Partially Subscribed")) {
			if (!product.status.equals("Not Subscribed") && !product.status.equals("Subscribed") && !product.status.equals("Unknown")) {
				Assert.assertEquals(product.status,"Partially Subscribed","Installed product '"+product.productName+"' status is Partially Subscribed.");
				Flag = "true";
			}
		}
		Assert.assertEquals(Flag, "true","Verified Partially Subscribed installed product(s).");
	}

	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(description = "Auto-heal for Expired subscription", groups = {
			"AutohealForExpired", "blockedByBug-746088","blockedByBug-907638","blockedByBug-907400"}, enabled = true)
	public void VerifyAutohealForExpiredSubscription() throws JSONException,
	Exception {
		List<String> Expiredproductid = new ArrayList<String>();
		clienttasks.register(sm_clientUsername, sm_clientPassword,
				sm_clientOrg, null, null, null, null, null, null, null,
				(String) null, null, null, null, true, null, null, null, null);
		clienttasks.service_level(null, null, null, true, null, null, null,
				null, null, null, null, null);
		clienttasks.run_rhsmcertd_worker(true);
		InstalledProduct  Shared_beforeExpiry= InstalledProduct.findFirstInstanceWithMatchingFieldFromList("productId", "88888", clienttasks.getCurrentlyInstalledProducts());
		if(Shared_beforeExpiry.status.equals("Not Subscribed")){
			throw new SkipException(
					"No subscriptions are available for testing");
		}else{
			File expectCertFile = new File(System.getProperty("automation.dir",
					null) + "/expiredcerts/Expiredcert.pem");
			RemoteFileTasks.putFile(client.getConnection(),
					expectCertFile.toString(), "/root/", "0755");
			clienttasks.importCertificate_("/root/Expiredcert.pem");


			for (InstalledProduct product : clienttasks
					.getCurrentlyInstalledProducts()) {
				if (product.status.equals("Expired"))
					Expiredproductid.add(product.productId);
			}
			if ((Expiredproductid.size() == 0)) {
				throw new SkipException(
						"No expired products are available for testing");
			} else {
				clienttasks.run_rhsmcertd_worker(true);
				for (InstalledProduct product : clienttasks.getCurrentlyInstalledProducts()) {
					for (int i = 0; i < Expiredproductid.size(); i++) {

						if (product.productId.equals(Expiredproductid.get(i)))
							Assert.assertEquals(product.status, "Subscribed");

					}
				}
			}}
	}

	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(description = "Auto-heal for subscription", groups = {"AutoHeal","blockedByBug-907638","blockedByBug-726411","blockedByBug-907400"}, enabled = true)
	@ImplementsNitrateTest(caseId = 119327)
	public void VerifyAutohealForSubscription() throws JSONException, Exception {
		/* unnecessary
		Integer healFrequency = 2;
		 */
		clienttasks.register(sm_clientUsername, sm_clientPassword,sm_clientOrg, null, null, null, null, null, null, null,(String) null, null, null, null, true, null, null, null, null);
		/* unnecessary; the consumer's autoheal attribute defaults to true
		clienttasks.unsubscribeFromAllOfTheCurrentlyConsumedProductSubscriptions();
		String consumerId = clienttasks.getCurrentConsumerId();
		clienttasks.autoheal(null, true, null, null, null, null);
		clienttasks.service_level_(null, null, null, true, null, null, null,null, null, null, null, null);
		 */
		/* takes too long; calling run_rhsmcertd_worker with autoheal instead...
		clienttasks.restart_rhsmcertd(null, healFrequency, true);	// argument assertCertificatesUpdate should be true for this test (and most all tests)  
		SubscriptionManagerCLITestScript.sleep(3 * 60 * 1000);
		 */
		Assert.assertTrue(clienttasks.getCurrentEntitlementCerts().isEmpty(), "After immediately registering with force, there are no entitlements attached.");
		clienttasks.run_rhsmcertd_worker(true);
		List<EntitlementCert> certs = clienttasks.getCurrentEntitlementCerts();
		List<ProductSubscription> consumed = clienttasks.getCurrentlyConsumedProductSubscriptions();
		log.info("Currently the consumed products are" + consumed.size());
		// this assertion assumes that the currently available subscriptions provide coverage for the currently installed products
		Assert.assertTrue(!clienttasks.getCurrentEntitlementCerts().isEmpty(), "Asserting that entitlement certs have been granted to the system indicating that autoheal was successful invoked to cover its currently installed products.");
	}

	/**
	 * @author skallesh
	 * @throws JSONException
	 * @throws Exception
	 */
	@Test(description = "Auto-heal with SLA",	// TODO Add some more description; has same description as VerifyAutohealWithSLA()
			groups = {"AutoHealFailForSLA"}, enabled = true)
	public void VerifyAutohealFailForSLA() throws JSONException, Exception {

		List<ProductCert> productCerts = clienttasks.getCurrentProductCerts();
		clienttasks.register(sm_clientUsername, sm_clientPassword,
				sm_clientOrg, null, null, null, null, null, null, null,
				(String) null, null, null, null, true, null, null, null, null);
		List<String> availableServiceLevelData = clienttasks
				.getCurrentlyAvailableServiceLevels();
		String availableService = availableServiceLevelData.get(randomGenerator
				.nextInt(availableServiceLevelData.size()));
		clienttasks.service_level(null, null, availableService, null, null, null, null,
				null, null, null, null, null);
		clienttasks.unsubscribe(true,(BigInteger)null, null, null, null);
		clienttasks.subscribe(true, null, (String) null, null,
				null, null, null, null, null, null, null, null);
		for (InstalledProduct installedProduct : clienttasks
				.getCurrentlyInstalledProducts()) {

			if (installedProduct.status.equalsIgnoreCase("Subscribed") || installedProduct.status.equalsIgnoreCase(
					"Partially Subscribed")) {

				ProductCert productCert = ProductCert.findFirstInstanceWithMatchingFieldFromList("productId", installedProduct.productId, productCerts);
				moveProductCertFiles(productCert.file.getName());
			}
		}
		clienttasks.unsubscribe(true,(BigInteger)null, null, null, null);
		List<EntitlementCert> certsbeforeRHSMService = clienttasks
				.getCurrentEntitlementCerts();
		log.info("cert contents are " + certsbeforeRHSMService);

		clienttasks.run_rhsmcertd_worker(true);
		List<ProductSubscription> consumed = clienttasks.getCurrentlyConsumedProductSubscriptions();
		Assert.assertTrue((consumed.isEmpty()), "autoheal has failed");
		List<EntitlementCert> certs = clienttasks.getCurrentEntitlementCerts();
		System.out.println(certs.size());
	}


	/**
	 * @author skallesh
	 * @throws IOException 
	 */

	@Test(description = "subscription-manager: subscribe multiple pools in incorrect format", groups = {
			"MysubscribeTest", "blockedByBug-772218" }, enabled = true)
	public void VerifyIncorrectSubscriptionFormat() throws IOException {
		clienttasks.register(sm_clientUsername, sm_clientPassword,
				sm_clientOrg, null, null, null, null, null, null, null,
				(String) null, null, null, null, true, null, null, null, null);
		List<String> poolid = new ArrayList<String>();
		for (SubscriptionPool pool : clienttasks
				.getCurrentlyAllAvailableSubscriptionPools()) {
			poolid.add(pool.poolId);
		}
		if (poolid.isEmpty())
			throw new SkipException(
					"Cannot randomly pick a pool for subscribing when there are no available pools for testing.");
		int i = randomGenerator.nextInt(poolid.size());
		int j = randomGenerator.nextInt(poolid.size());
		if (i == j) {
			j = randomGenerator.nextInt(poolid.size());
		}
		SSHCommandResult subscribeResult = subscribeInvalidFormat_(null,
				null, poolid.get(i), poolid.get(j), null, null, null, null,
				null, null, null, null);
		Assert.assertEquals(subscribeResult.getStdout().trim(),
				"cannot parse argument: " + poolid.get(j));


	}

	/**
	 * @author skallesh
	 * @throws Exception
	 */
	@Test(description = "Verify that Entitlement Start Dates is the Subscription Start Date ", groups = {
			"VerifyEntitlementStartDate_Test",
	"blockedByBug-670831" }, enabled = true)
	public void VerifyEntitlementStartDate_Test() throws JSONException,
	Exception {
		// unnecessary		clienttasks.autoheal(null, null, true, null, null, null);
		clienttasks.register(sm_clientUsername, sm_clientPassword,
				sm_clientOrg, null, null, null, null, null, null, null,
				(String) null, null, null, null, true, false, null, null, null);
		// unnecessary		clienttasks.unsubscribe(true, (BigInteger)null, null, null, null);
		// takes too long		for (SubscriptionPool pool : clienttasks.getCurrentlyAvailableSubscriptionPools()) {
		for (SubscriptionPool pool : getRandomSubsetOfList(clienttasks.getCurrentlyAvailableSubscriptionPools(),5)) {	
			JSONObject jsonPool = new JSONObject(CandlepinTasks.getResourceUsingRESTfulAPI(
					sm_clientUsername, sm_clientPassword, sm_serverUrl,"/pools/" + pool.poolId));
			Calendar subStartDate = parseISO8601DateString(jsonPool.getString("startDate"), "GMT");
			EntitlementCert entitlementCert = clienttasks.getEntitlementCertFromEntitlementCertFile(clienttasks.subscribeToSubscriptionPool_(pool));
			Calendar entStartDate = entitlementCert.validityNotBefore;
			Assert.assertEquals(entStartDate,subStartDate,"The entitlement start date '"
					+ EntitlementCert.formatDateString(entStartDate)
					+ "' granted from pool " + pool.poolId
					+ " should equal its subscription start date '"
					+ OrderNamespace.formatDateString(subStartDate)
					+ "'.");
		}
	}

	/**
	 * @author skallesh
	 * @throws Exception
	 */
	@Test(description = "Verify if architecture for auto-subscribe test", groups = { "VerifyarchitectureForAutobind_Test","blockedByBug-664847" }, enabled = true)
	public void VerifyarchitectureForAutobind_Test() throws Exception {

		clienttasks.register(sm_clientUsername, sm_clientPassword,
				sm_clientOrg, null, null, null, null, null, null, null,
				(String) null, null, null, null, true, null, null, null, null);
		Map<String, String> result = clienttasks.getFacts();
		String arch = result.get("uname.machine");
		List<String> cpu_arch = new ArrayList<String>();
		String input = "x86_64|i686|ia64|ppc|ppc64|s390x|s390";
		String[] values = input.split("\\|");
		Boolean flag = false;
		Boolean expected = true;
		for (int i = 0; i < values.length; i++) {
			cpu_arch.add(values[i]);
		}

		Pattern p = Pattern.compile(arch);
		Matcher matcher = p.matcher(input);
		while (matcher.find()) {
			String pattern_ = matcher.group();
			cpu_arch.remove(pattern_);

		}
		String architecture = cpu_arch.get(randomGenerator.nextInt(cpu_arch
				.size()));
		for (SubscriptionPool pool : clienttasks
				.getCurrentlyAvailableSubscriptionPools()) {
			if ((pool.subscriptionName).contains(" " + architecture)) {
				flag = true;
				Assert.assertEquals(flag, expected);
			}

		}

		for (SubscriptionPool pools : clienttasks
				.getCurrentlyAllAvailableSubscriptionPools()) {
			if ((pools.subscriptionName).contains(architecture)) {
				flag = true;
				Assert.assertEquals(flag, expected);
			}

		}
		Map<String, String> factsMap = new HashMap<String, String>();
		factsMap.put("uname.machine", String.valueOf(architecture));
		clienttasks.createFactsFileWithOverridingValues(factsMap);
		clienttasks.facts(null, true, null, null, null);
	}

	/**
	 * @author skallesh
	 * @throws Exception
	 */
	@Test(	description = "Verify that rhsm.log reports all products provided by an attached subsubscription.",
			groups = {"blockedByBug-668032"/*,"blockedByBug-1016300"*/ },
			enabled = true)
	public void VerifyRhsmLogsProvidedProducts_Test() {
		/* re-implementing this test...
		Boolean actual = true;

		clienttasks.register(sm_clientUsername, sm_clientPassword,
				sm_clientOrg, null, null, null, null, null, null, null,
				(String) null, null, null, null, true, null, null, null, null);
		String LogMarker = System.currentTimeMillis()+" Testing ***************************************************************";
		RemoteFileTasks.markFile(client, clienttasks.rhsmLogFile, LogMarker);
		for (SubscriptionPool pool : clienttasks.getCurrentlyAllAvailableSubscriptionPools()) {
			List<String> providedProducts = CandlepinTasks.getPoolProvidedProductIds(sm_clientUsername,sm_clientPassword, sm_serverUrl, pool.poolId);
			if (((providedProducts.size()) > 2) ||((providedProducts.size()) == 2) ) {
				actual =false;
				clienttasks.subscribe_(null, null, pool.poolId, null, null,
						null, null, null, null, null, null);

			}
			}
		if(actual==false){
			actual=true;
			Boolean flag = RegexInRhsmLog("@ /etc/pki/entitlement",RemoteFileTasks.getTailFromMarkedFile(client, clienttasks.rhsmLogFile, LogMarker, null));
			Assert.assertEquals(flag, actual);
		}else throw new SkipException("no bundled products available for testing");	
		 */
		client.runCommandAndWait("rm -f "+clienttasks.rhsmLogFile);	// remove it because it occasionally gets backed up to rhsm.log.1 in the midst of a pair of calls to RemoteFileTasks.markFile(...) and RemoteFileTasks.getTailFromMarkedFile(...)
		clienttasks.register(sm_clientUsername, sm_clientPassword, sm_clientOrg, null, null, null, null, null, null, null, (String)null, null, null, null, true, false, null, null, null);
		boolean foundSubscriptionProvidingMultipleProducts = false;
		for (SubscriptionPool pool : clienttasks.getCurrentlyAvailableSubscriptionPools()) {
			if (pool.provides.size()>2) {
				foundSubscriptionProvidingMultipleProducts = true;

				String logMarker = System.currentTimeMillis()+" VerifyRhsmLogsProvidedProducts_Test ****************************************";
				RemoteFileTasks.markFile(client, clienttasks.rhsmLogFile, logMarker);
				File serialFile = clienttasks.subscribeToSubscriptionPool(pool, sm_clientUsername, sm_clientPassword, sm_serverUrl);
				BigInteger serialNumber = clienttasks.getSerialNumberFromEntitlementCertFile(serialFile);
				String rhsmLogTail = RemoteFileTasks.getTailFromMarkedFile(client, clienttasks.rhsmLogFile, logMarker, serialNumber.toString());
				//2014-02-14 11:41:34,891 [INFO] subscription-manager @certlib.py:245 - certs updated:
				//	Total updates: 1
				//	Found (local) serial# []
				//	Expected (UEP) serial# [5098711034167311680]
				//	Added (new)
				//	  [sn:5098711034167311680 (Clustering Bits,) @ /etc/pki/entitlement/5098711034167311680.pem]
				//	  [sn:5098711034167311680 (Awesome OS Server Bits,) @ /etc/pki/entitlement/5098711034167311680.pem]
				//	  [sn:5098711034167311680 (Load Balancing Bits,) @ /etc/pki/entitlement/5098711034167311680.pem]
				//	  [sn:5098711034167311680 (Large File Support Bits,) @ /etc/pki/entitlement/5098711034167311680.pem]
				//	  [sn:5098711034167311680 (Shared Storage Bits,) @ /etc/pki/entitlement/5098711034167311680.pem]
				//	  [sn:5098711034167311680 (Management Bits,) @ /etc/pki/entitlement/5098711034167311680.pem]
				//	Deleted (rogue):
				//	  <NONE>

				// assert that the rhsm.log reports a message for all of the products provided for by this entitlement
				for (String providedProduct : pool.provides) {
					if (providedProduct.equals("Awesome OS Server Bundled")) continue;	// avoid Bug 1016300 - the "Provides:" field in subscription-manager list --available should exclude "MKT" products.
					String expectedLogMessage = String.format("[sn:%s (%s,) @ %s]",serialNumber.toString(), providedProduct, serialFile.getPath());
					Assert.assertTrue(rhsmLogTail.contains(expectedLogMessage), "Log file '"+clienttasks.rhsmcertdLogFile+"' reports expected message '"+expectedLogMessage+"'.");
				}
			}
		}
		if (!foundSubscriptionProvidingMultipleProducts) throw new SkipException("Could not find and available subscriptions providing multiple products to test Bug 668032.");
	}

	/**
	 * @author skallesh
	 * @throws Exception
	 */
	@Test(description = "Verify if the status of installed products match when autosubscribed,and when you subscribe all the available products ", groups = {"VerifyFuturesubscription_Test", "blockedByBug-746035" }, enabled = true)
	public void VerifyFuturesubscription_Test() throws Exception {
		clienttasks.register(sm_clientUsername, sm_clientPassword,
				sm_clientOrg, null, null, null, null, null, null, null,
				(String) null, null, null, null, true, false, null, null, null);
		String productId = null;
		clienttasks.subscribe(true, null, (String) null, null, null, null,
				null, null, null, null, null, null);
		List<InstalledProduct> installedproducts = clienttasks.getCurrentlyInstalledProducts();
		clienttasks.unsubscribeFromTheCurrentlyConsumedSerialsCollectively();
		Calendar now = new GregorianCalendar();
		DateFormat yyyy_MM_dd_DateFormat = new SimpleDateFormat("yyyy-MM-dd");
		now.add(Calendar.YEAR, 1);
		now.add(Calendar.DATE, 1);
		String onDateToTest = yyyy_MM_dd_DateFormat.format(now.getTime());
		List<SubscriptionPool> availOnDate = getAvailableFutureSubscriptionsOndate(onDateToTest);
		if(availOnDate.size()==0) throw new SkipException("Sufficient future pools are not available");
		clienttasks.subscribe(null, null, availOnDate.get(randomGenerator.nextInt(availOnDate.size())).poolId, null, null, null, null, null, null, null, null, null);


		for (InstalledProduct installedproduct : clienttasks
				.getCurrentlyInstalledProducts()) {
			if (installedproduct.status.equals("Future Subscription")) {

				productId=installedproduct.productId;
			}
		}
		InstalledProduct installedPro = InstalledProduct.findFirstInstanceWithMatchingFieldFromList("productId", productId, installedproducts);
		System.out.println(installedPro);
       if(installedPro.status.equals("Not Subscribed"))throw new SkipException("pools are not available for testing");
		Assert.assertTrue(!(productId==null), "Found installed product(s) with a Future Subscription Status needed to attempt this test.");

		clienttasks.subscribe(true, null, (String) null, null, null, null,
				null, null, null, null, null, null);

		boolean assertedFutureSubscriptionIsNowSubscribed = false;
		for (InstalledProduct installedProduct : clienttasks.getCurrentlyInstalledProducts()) {
				if (installedProduct.productId.equals(productId)) {
					List<String> installedProductArches = new ArrayList<String>(Arrays.asList(installedProduct.arch.trim().split(" *, *")));	// Note: the arch can be a comma separated list of values
					if (installedProductArches.contains("x86")) {installedProductArches.addAll(Arrays.asList("i386","i486","i586","i686"));}	// Note: x86 is a general alias to cover all 32-bit intel microprocessors, expand the x86 alias
					if (installedProductArches.contains(clienttasks.arch) || installedProductArches.contains("ALL")) {
						Assert.assertEquals(installedProduct.status.trim(),
								"Subscribed", "Previously installed product '"+installedProduct.productName+"' covered by a Future Subscription should now be covered by a current subscription after auto-subscribing.");
						assertedFutureSubscriptionIsNowSubscribed = true;
					} else {
						Assert.assertEquals(installedProduct.status.trim(),
								"Future Subscription", "Mismatching arch installed product '"+installedProduct.productName+"' (arch='"+installedProduct.arch+"') covered by a Future Subscription should remain unchanged after auto-subscribing.");			
					}
				
			}
		}
		Assert.assertTrue(assertedFutureSubscriptionIsNowSubscribed,"Verified at least one previously installed product covered by a Future Subscription is now covered by a current subscription after auto-subscribing.");
	}

	protected Calendar parseISO8601DateString(String dateString, String timeZone) {
		String iso8601DatePattern = "yyyy-MM-dd'T'HH:mm:ss.SSSZ";
		String datePattern = iso8601DatePattern;
		if (timeZone == null)
			datePattern = datePattern.replaceFirst("Z$", ""); // strip off final
		// timezone
		// offset symbol
		// from
		// iso8601DatePattern
		return parseDateStringUsingDatePattern(dateString, datePattern,
				timeZone);
	}

	protected Calendar parseDateStringUsingDatePattern(String dateString,
			String datePattern, String timeZone) {
		try {
			DateFormat dateFormat = new SimpleDateFormat(datePattern); // format="yyyy-MM-dd'T'HH:mm:ss.SSSZ"
			// will
			// parse
			// dateString="2012-02-08T00:00:00.000+0000"
			if (timeZone != null)
				dateFormat.setTimeZone(TimeZone.getTimeZone(timeZone)); // timeZone="GMT"
			Calendar calendar = new GregorianCalendar();
			calendar.setTimeInMillis(dateFormat.parse(dateString).getTime());
			return calendar;
		} catch (ParseException e) {
			log.warning("Failed to parse " + (timeZone == null ? "" : timeZone)
					+ " date string '" + dateString + "' with format '"
					+ datePattern + "':\n" + e.getMessage());
			return null;
		}
	}

	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(description = "Verify if the status of installed products match when autosubscribed,and when you subscribe all the available products ",
			groups = { "VerifyautosubscribeTest" }, enabled = true)
	public void VerifyautosubscribeTest() throws JSONException, Exception {

		List<String> ProductIdBeforeAuto = new ArrayList<String>();
		List<String> ProductIdAfterAuto = new ArrayList<String>();
		clienttasks.deleteFactsFileWithOverridingValues();
		clienttasks.register(sm_clientUsername, sm_clientPassword,
				sm_clientOrg, null, null, null, null, null, null, null,
				(String) null, null, null, null, true, null, null, null, null);
		clienttasks.unsubscribe(true, (BigInteger) null, null, null, null);
		clienttasks.subscribeToTheCurrentlyAvailableSubscriptionPoolsCollectively();
		for (InstalledProduct installedProductsBeforeAuto : clienttasks
				.getCurrentlyInstalledProducts()) {
			if (installedProductsBeforeAuto.status.equals("Subscribed"))
				ProductIdBeforeAuto.add(installedProductsBeforeAuto.productId);
		}

		clienttasks.unsubscribe(true, (BigInteger) null, null, null, null);
		clienttasks.subscribe_(true, null, (String) null, null, null, null,
				null, null, null, null, null, null);
		for (InstalledProduct installedProductsAfterAuto : clienttasks
				.getCurrentlyInstalledProducts()) {
			if (installedProductsAfterAuto.status.equals("Subscribed"))
				ProductIdAfterAuto.add(installedProductsAfterAuto.productId);
		}
		Assert.assertEquals(ProductIdBeforeAuto.size(),
				ProductIdAfterAuto.size());
	}

	/**
	 * @author skallesh
	 * @throws Exception
	 */
	@Test(description = "Verify if autosubscribe ignores socket count on non multi-entitled subscriptions ", groups = { "VerifyautosubscribeIgnoresSocketCount_Test","blockedByBug-743704" }, enabled = true)
	public void VerifyautosubscribeIgnoresSocketCount_Test() throws Exception {
		//	InstalledProduct installedProduct = InstalledProduct.findFirstInstanceWithMatchingFieldFromList("productId", "1000000000000023", clienttasks.getCurrentlyInstalledProducts());

		String socketvalue=null;
		clienttasks.register(sm_clientUsername, sm_clientPassword,
				sm_clientOrg, null, null, null, null, null, null, null,
				(String) null, null, null, null, true, false, null, null, null);

		Map<String, String> factsMap = new HashMap<String, String>();
		factsMap.put("cpu.cpu_socket(s)", String.valueOf(4));
		clienttasks.createFactsFileWithOverridingValues(factsMap);
		clienttasks.facts(null, true, null, null, null);

		clienttasks.subscribe(true, null, (String) null, null, null, null,
				null, null, null, null, null, null);
		Boolean Flag=false;

		factsMap.put("cpu.cpu_socket(s)", String.valueOf(1));
		clienttasks.createFactsFileWithOverridingValues(factsMap);
		clienttasks.facts(null, true, null, null, null);
		clienttasks.unsubscribeFromAllOfTheCurrentlyConsumedProductSubscriptions();
		clienttasks.subscribe(true, null, (String) null, null, null, null,
				null, null, null, null, null, null);


		InstalledProduct installedProductsAfterAuto = InstalledProduct.findFirstInstanceWithMatchingFieldFromList("productId", "1000000000000023", clienttasks.getCurrentlyInstalledProducts());


		if(installedProductsAfterAuto.status.equals("Subscribed")){
			Flag=true;



			Assert.assertTrue(Flag,"Auto-attach doesnot ignore socket count");
		}
	}







	/**
	 * @author skallesh
	 * @throws Exception
	 * @throws JSONException
	 */
	@Test(description = "subscription-manager: entitlement key files created with weak permissions", groups = {
			"MykeyTest", "blockedByBug-720360" }, enabled = true)
	public void VerifyKeyFilePermissions() throws JSONException, Exception {
		clienttasks.register(sm_clientUsername, sm_clientPassword,
				sm_clientOrg, null, null, null, null, null, null, null,
				(String) null, null, null, null, true, null, null, null, null);
		clienttasks.subscribeToTheCurrentlyAvailableSubscriptionPoolsCollectively();
		String subscribeResult = getEntitlementCertFilesWithPermissions();
		Pattern p = Pattern.compile("[,\\s]+");
		String[] result = p.split(subscribeResult);
		String permissions = "-rw-------";	// RHEL5
		if (Integer.valueOf(clienttasks.redhatReleaseX)>5) permissions = "-rw-------.";
		for (int i = 0; i < result.length; i++) {
			Assert.assertEquals(result[i], permissions,
					"permission for etc/pki/entitlement/<serial>-key.pem is -rw-------");
			i++;
		}
	}

	@BeforeGroups(groups = "setup", value = { /*"VerifyDistinct",*/
			/*"VerifyStatusForPartialSubscription",*/ /*"AutoHeal",*/
			/*"AutoHealFailForSLA",*/ /*"VerifyautosubscribeTest",*/
			/* "BugzillaTests", CAUSES THIS TO RUN BEFORE THE CLASS; NOT WHAT WE WANTED */ /*"autohealPartial",*/
	/*"VerifyEntitlementStartDate_Test",*/ /*"reregister"*/ }, enabled = true)
	public void unsubscribeBeforeGroup() {
		clienttasks.unsubscribe(true, (BigInteger) null, null, null, null);
	}

	@BeforeGroups(groups = "setup", value = { /*"VerifyDistinct",*/ /*"AutoHeal",*/
	/*"autohealPartial",*/ /*"BugzillaTests" CAUSES THIS TO RUN BEFORE THE CLASS; NOT WHAT WE WANTED */ }, enabled = true)
	public void unsetServicelevelBeforeGroup() {
		clienttasks.service_level_(null, null, null, true, null, null, null,
				null, null, null, null, null);
	}



	@BeforeGroups(groups = "setup", value = { /*"VerifyDistinct",*/ /*"AutoHeal",*/
			/*"VerifyStatusForPartialSubscription",*/ /*"autohealPartial",*/
	/*"VerifyEntitlementStartDate_Test",*/ /*"BugzillaTests" CAUSES THIS TO RUN BEFORE THE CLASS; NOT WHAT WE WANTED*/ }, enabled = true)
	public void setHealFrequencyGroup() {
		List<String[]> listOfSectionNameValues = new ArrayList<String[]>();
		listOfSectionNameValues.add(new String[] { "rhsmcertd",
				"autoAttachInterval".toLowerCase(), "1440" });
		clienttasks.config(null, null, true, listOfSectionNameValues);
		String param = clienttasks.getConfFileParameter(
				clienttasks.rhsmConfFile, "rhsmcertd", "autoAttachInterval");

		Assert.assertEquals(param, "1440");
	}

	@AfterGroups(groups = "setup", value = {"VerifyRepoFileExistance"}, enabled = true)
	public void TurnonRepos(){
		List<String[]> listOfSectionNameValues = new ArrayList<String[]>();
		listOfSectionNameValues = new ArrayList<String[]>();
		listOfSectionNameValues.add(new String[] { "rhsm","manage_repos", "1" });
		clienttasks.config(null, null, true, listOfSectionNameValues);
	}

	/* this methods looks more like a Test than a BeforeGroups configuration method; changing to a Test...
	@BeforeGroups(groups = "setup", value = { "BugzillaTests","VerifyEntitlementStartDate_Test","unsubscribeImportedcert" }, enabled = true)
	 */
	@Test(	description = "verify that the autoheal attribute of a new system consumer defaults to true",
			groups = {},
			enabled = true)
	public void VerifyAutohealAttributeDefaultsToTrueForNewSystemConsumer_Test()
			throws Exception {
		clienttasks.register(sm_clientUsername,sm_clientPassword, sm_clientOrg, null, null, null,
				null, null, null, null, (String) null, null, null,
				null, true, null, null, null, null);
		String consumerId = clienttasks.getCurrentConsumerId();
		/* unnecessary; we should be getting the consumer without setting the autoheal attribute
		JSONObject jsonConsumer = CandlepinTasks.setAutohealForConsumer(
				sm_clientUsername, sm_clientPassword, sm_serverUrl, consumerId,
				true);
		 */
		JSONObject jsonConsumer = new JSONObject(CandlepinTasks.getResourceUsingRESTfulAPI(sm_clientUsername, sm_clientPassword, sm_serverUrl, "/consumers/"+consumerId));

		Assert.assertTrue(jsonConsumer.getBoolean("autoheal"),
				"A new system consumer's autoheal attribute value defaults to true.");
	}


	@BeforeClass(groups = "setup")
	public void rememberConfiguredFrequencies() {
		if (clienttasks == null) return;
		configuredHealFrequency = Integer.valueOf(clienttasks.getConfFileParameter(clienttasks.rhsmConfFile, "rhsmcertd","autoAttachInterval"));
		configuredCertFrequency = Integer.valueOf(clienttasks.getConfFileParameter(clienttasks.rhsmConfFile, "rhsmcertd","certCheckInterval"));
		configuredHostname=clienttasks.getConfFileParameter(clienttasks.rhsmConfFile, "server","hostname");
	}


	/* this effectively runs BeforeClass since BugzillaTests is tagged to the entire class; this is not what we wanted
	@BeforeGroups(groups = "setup", value = { "BugzillaTests"}, enabled = true)
	 */
	@AfterGroups(groups = {"setup"}, value = {"VerifyRHSMCertdLogging"/*,"AutohealForExpired"*/})
	@AfterClass(groups = "setup")	// called after class for insurance
	public void restoreConfiguredFrequencies() {
		if (clienttasks == null) return;
		clienttasks.restart_rhsmcertd(configuredCertFrequency, configuredHealFrequency, null);
	}


	@AfterGroups(groups = { "setup" }, value = {"VerifySubscriptionOf",
			"VerifySystemCompliantFact","ValidityAfterOversubscribing",
			"UpdateWithNoInstalledProducts","VerifyStatusCheck",
			"VerifyStartEndDateOfSubscription"/*,"InstalledProductMultipliesAfterSubscription"*/,"AutoHealFailForSLA","VerifyautosubscribeIgnoresSocketCount_Test"})
	@AfterClass(groups = "setup")
	public void restoreProductCerts() throws IOException {
		client.runCommandAndWait("mv " + "/root/temp1/*" + " "
				+ clienttasks.productCertDir);
		client.runCommandAndWait("rm -rf " + "/root/temp1");
	}

	@AfterGroups(groups = { "setup" }, value = { /*"VerifyautosubscribeTest",*/"VerifyStatusForPartialSubscription","certificateStacking",
			"VerifyautosubscribeIgnoresSocketCount_Test","VerifyDistinct","autohealPartial","VerifyFactsListByOverridingValues"})
	@AfterClass(groups = { "setup" })	// called after class for insurance
	public void deleteFactsFileWithOverridingValues() {
		clienttasks.deleteFactsFileWithOverridingValues();
	}

	protected void configureTmpProductCertDirWithInstalledProductCerts(List<ProductCert> installedProductCerts) {
		if (rhsmProductCertDir==null) {
			rhsmProductCertDir = clienttasks.getConfFileParameter(clienttasks.rhsmConfFile, "rhsm", "productCertDir");
			Assert.assertNotNull(rhsmProductCertDir);
		}
		log.info("Initializing a new product cert directory with the currently installed product certs for this test...");
		RemoteFileTasks.runCommandAndAssert(client,"mkdir -p "+tmpProductCertDir,Integer.valueOf(0));
		RemoteFileTasks.runCommandAndAssert(client,"rm -f "+tmpProductCertDir+"/*.pem",Integer.valueOf(0));
		for (ProductCert productCert : installedProductCerts) {
			RemoteFileTasks.runCommandAndAssert(client,"cp "+productCert.file+" "+tmpProductCertDir,Integer.valueOf(0));
		}
		
		System.out.println(RemoteFileTasks.runCommandExpectingNoTracebacks(client,"ls -l "+tmpProductCertDir).getStdout());
		clienttasks.updateConfFileParameter(clienttasks.rhsmConfFile, "productCertDir", tmpProductCertDir);
	}
	@BeforeGroups(groups="setup", value = {"VerifyStatusCheck"})
	@AfterGroups(groups="setup", value = {"VerifyStatusCheck","certificateStacking"})
	@AfterClass(groups="setup")	// called after class for insurance
	public void restoreRhsmProductCertDir() {
		if (clienttasks==null) return;
		if (rhsmProductCertDir==null) return;	
		log.info("Restoring the originally configured product cert directory...");
		clienttasks.updateConfFileParameter(clienttasks.rhsmConfFile, "productCertDir", rhsmProductCertDir);
	}
	protected String rhsmProductCertDir = null;
	protected final String tmpProductCertDir = "/tmp/sm-tmpProductCertDir";

	// Protected methods
	// ***********************************************************************

	protected String setDate(String hostname, String user, String passphrase,
			String privatekey, String datecmd) throws IOException {
		SSHCommandRunner sshHostnameCommandRunner = new SSHCommandRunner(hostname, user, passphrase, privatekey,
				null);
		return(sshHostnameCommandRunner.runCommandAndWait(datecmd).getStdout());

	}
	protected String getDate(String hostname, String user, String passphrase,
			String privatekey,Boolean flag) throws IOException, ParseException {
		SSHCommandRunner sshHostnameCommandRunner = new SSHCommandRunner(hostname, user, passphrase, privatekey, null);
		if(flag)
			return(sshHostnameCommandRunner.runCommandAndWait("date +\"%F\"").getStdout());
		else
			return(sshHostnameCommandRunner.runCommandAndWait("date --date='yesterday' '+%F'").getStdout());
	}

	protected void moveProductCertFiles(String filename) throws IOException {
		String installDir="/root/temp1/";
		if(!(RemoteFileTasks.testExists(client, installDir))){
			client.runCommandAndWait("mkdir " + installDir);
		}
		client.runCommandAndWait("mv " + clienttasks.productCertDir + "/"+ filename + " " + "/root/temp1/");
	}


	protected String getEntitlementCertFilesWithPermissions() throws IOException {
		// client is already instantiated		client = new SSHCommandRunner(sm_clientHostname, sm_sshUser, sm_sshKeyPrivate,sm_sshkeyPassphrase,null);
		String lsFiles = client.runCommandAndWait(
				"ls -l " + clienttasks.entitlementCertDir + "/*-key.pem"
						+ " | cut -d " + "' '" + " -f1,9").getStdout();
		return lsFiles;
	}

	protected SSHCommandResult unsubscribeFromMultipleEntitlementsUsingSerialNumber(
			BigInteger SerialNumOne, BigInteger SerialNumTwo) throws IOException {
		/* this task is implemented by SubscriptionManagerTasks
// client is already instantiated		client = new SSHCommandRunner(sm_clientHostname, sm_sshUser, sm_sshKeyPrivate,sm_sshkeyPassphrase,null);
		String command = clienttasks.command;
		command += " unsubscribe";
		if (SerialNumOne != null && SerialNumTwo != null)
			command += " --serial=" + SerialNumOne + " " + "--serial="
					+ SerialNumTwo;

		// run command without asserting results
		return client.runCommandAndWait(command);
		 */
		return clienttasks.unsubscribe_(false, Arrays.asList(new BigInteger[]{SerialNumOne,SerialNumTwo}), null, null, null);
	}

	protected SSHCommandResult subscribeInvalidFormat_(Boolean auto,
			String servicelevel, String poolIdOne, String poolIdTwo,
			List<String> productIds, List<String> regtokens, String quantity,
			String email, String locale, String proxy, String proxyuser,
			String proxypassword) throws IOException {
		// client is already instantiated		client = new SSHCommandRunner(sm_clientHostname, sm_sshUser, sm_sshKeyPrivate,sm_sshkeyPassphrase,null);
		String command = clienttasks.command;
		command += " subscribe";
		if (poolIdOne != null && poolIdTwo != null)
			command += " --pool=" + poolIdOne + " " + poolIdTwo;

		// run command without asserting results
		return client.runCommandAndWait(command);
	}

	public Boolean RegexInRhsmLog(String logRegex, String input) {

		Pattern pattern = Pattern.compile(logRegex, Pattern.MULTILINE);
		Matcher matcher = pattern.matcher(input);
		int count = 0;
		Boolean flag = false;
		while (matcher.find()) {
			count++;
		}
		if (count >= 2) {
			flag = true;
		}
		return flag;

	}

	/**
	 * @return list of objects representing the subscription-manager list
	 *         --avail --ondate
	 */
	public List<SubscriptionPool> getAvailableFutureSubscriptionsOndate(
			String onDateToTest) {
		return SubscriptionPool.parse(clienttasks.list_(null, true, null, null,
				null, onDateToTest, null, null, null, null, null, null, null).getStdout());
	}

	protected List<String> listFutureSubscription_OnDate(Boolean available,
			String ondate) {
		List<String> PoolId = new ArrayList<String>();
		SSHCommandResult result = clienttasks.list_(true, true, null, null,
				null, ondate, null, null, null, null, null, null, null);
		List<SubscriptionPool> Pool = SubscriptionPool
				.parse(result.getStdout());
		for (SubscriptionPool availablePool : Pool) {
			if (availablePool.multiEntitlement) {
				PoolId.add(availablePool.poolId);
			}
		}

		return PoolId;
	}

	@DataProvider(name="getPackageFromEnabledRepoAndSubscriptionPoolData")
	public Object[][] getPackageFromEnabledRepoAndSubscriptionPoolDataAs2dArray() throws JSONException, Exception {
		return TestNGUtils.convertListOfListsTo2dArray(getPackageFromEnabledRepoAndSubscriptionPoolDataAsListOfLists());
	}

	protected List<List<Object>> getPackageFromEnabledRepoAndSubscriptionPoolDataAsListOfLists() throws JSONException, Exception {
		List<List<Object>> ll = new ArrayList<List<Object>>(); if (!isSetupBeforeSuiteComplete) return ll;
		if (clienttasks==null) return ll;
		if (sm_clientUsername==null) return ll;
		if (sm_clientPassword==null) return ll;

		// get the currently installed product certs to be used when checking for conditional content tagging
		List<ProductCert> currentProductCerts = clienttasks.getCurrentProductCerts();

		// assure we are freshly registered and process all available subscription pools
		clienttasks.register(sm_clientUsername, sm_clientPassword, sm_clientOrg, null, ConsumerType.system, null, null, null, null, null, (String)null, null, null, null, Boolean.TRUE, false, null, null, null);
		for (SubscriptionPool pool : clienttasks.getCurrentlyAvailableSubscriptionPools()) {

			File entitlementCertFile = 		clienttasks.subscribeToSubscriptionPool(pool,sm_serverAdminUsername,sm_serverAdminPassword,sm_serverUrl);
			Assert.assertNotNull(entitlementCertFile, "Found the entitlement cert file that was granted after subscribing to pool: "+pool);
			EntitlementCert entitlementCert = clienttasks.getEntitlementCertFromEntitlementCertFile(entitlementCertFile);
			for (ContentNamespace contentNamespace : entitlementCert.contentNamespaces) {
				if (!contentNamespace.type.equalsIgnoreCase("yum")) continue;
				if (contentNamespace.enabled && clienttasks.areAllRequiredTagsInContentNamespaceProvidedByProductCerts(contentNamespace, currentProductCerts)) {
					String repoLabel = contentNamespace.label;

					// find an available package that is uniquely provided by repo
					String pkg = clienttasks.findUniqueAvailablePackageFromRepo(repoLabel);
					if (pkg==null) {
						log.warning("Could NOT find a unique available package from repo '"+repoLabel+"' after subscribing to SubscriptionPool: "+pool);
					}

					// String availableGroup, String installedGroup, String repoLabel, SubscriptionPool pool
					ll.add(Arrays.asList(new Object[]{pkg, repoLabel, pool}));
				}
			}
			clienttasks.unsubscribeFromSerialNumber(clienttasks.getSerialNumberFromEntitlementCertFile(entitlementCertFile));

			// minimize the number of dataProvided rows (useful during automated testcase development)
			if (Boolean.valueOf(getProperty("sm.debug.dataProviders.minimize","false"))) break;
		}

		return ll;
	}

	/**
	 * @param startingMinutesFromNow
	 * @param endingMinutesFromNow
	 * @return poolId to the newly available SubscriptionPool
	 * @throws JSONException
	 * @throws Exception
	 */
	protected String createTestPool(int startingMinutesFromNow, int endingMinutesFromNow) throws JSONException, Exception  {	
		/* already implemented; calling CandlepinTasks.createSubscriptionAndRefreshPoolsUsingRESTfulAPI(...)

		Calendar startCalendar = new GregorianCalendar();
		startCalendar.add(Calendar.MINUTE, endingMinutesFromNow);
		Date endDate = startCalendar.getTime();
		startCalendar.add(Calendar.MINUTE, startingMinutesFromNow);
		Date startDate = startCalendar.getTime();

		// randomly choose a contract number
		Integer contractNumber = Integer.valueOf(getRandInt());

		// randomly choose an account number
		Integer accountNumber = Integer.valueOf(getRandInt());


		String productId = randomAvailableProductId;

		providedProducts.add("100000000000002");

		// create the subscription
		String requestBody = CandlepinTasks.createSubscriptionRequestBody(3, startDate, endDate, productId, contractNumber, accountNumber, providedProducts).toString();
		JSONObject jsonSubscription = new JSONObject(CandlepinTasks.postResourceUsingRESTfulAPI(sm_serverAdminUsername, sm_serverAdminPassword, sm_serverUrl, "/owners/" + ownerKey + "/subscriptions", requestBody));

		// refresh the pools
		JSONObject jobDetail = CandlepinTasks.refreshPoolsUsingRESTfulAPI(sm_serverAdminUsername, sm_serverAdminPassword, sm_serverUrl, ownerKey);
		jobDetail = CandlepinTasks.waitForJobDetailStateUsingRESTfulAPI(sm_serverAdminUsername,sm_serverAdminPassword,sm_serverUrl,jobDetail,"FINISHED", 5*1000, 1);

		// assemble an activeon parameter set to the start date so we can pass it on to the REST API call to find the created pool
		DateFormat iso8601DateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ");	

		// "2012-02-08T00:00:00.000+0000"
		String iso8601FormatedDateString = iso8601DateFormat.format(startDate);
		iso8601FormatedDateString = iso8601FormatedDateString.replaceFirst("(..$)", ":$1");	


		// "2012-02-08T00:00:00.000+00:00"	// see https://bugzilla.redhat.com/show_bug.cgi?id=720493 // http://books.xmlschemata.org/relaxng/ch19-77049.html requires a colon in the time zone for xsd:dateTime
		String urlEncodedActiveOnDate = java.net.URLEncoder.encode(iso8601FormatedDateString, "UTF-8");	// "2012-02-08T00%3A00%3A00.000%2B00%3A00"	encode the string to escape the colons and plus signs so it can be passed as a parameter on an http call

		// loop through all pools available to owner and find the newly created poolid corresponding to the new subscription id activeon startDate
		String poolId = null;
		String subscriptionId=null;
		JSONArray jsonPools = new JSONArray(CandlepinTasks.getResourceUsingRESTfulAPI(sm_serverAdminUsername,sm_serverAdminPassword,sm_serverUrl,"/owners/"+ownerKey+"/pools"+"?activeon="+urlEncodedActiveOnDate));	
		for (int i = 0; i < jsonPools.length(); i++) {
			JSONObject jsonPool = (JSONObject) jsonPools.get(i);
			//if (contractNumber.equals(jsonPool.getInt("contractNumber"))) {
			if (jsonPool.getString("subscriptionId").equals(jsonSubscription.getString("id"))) {

				poolId = jsonPool.getString("id");
				break;
			}
		}
		Assert.assertNotNull(poolId,"Found newly created pool corresponding to the newly created subscription with id: "+jsonSubscription.getString("id"));
		log.info("The newly created subscription pool with id '"+poolId+"' will start '"+startingMinutesFromNow+"' minutes from now.");
		log.info("The newly created subscription pool with id '"+poolId+"' will expire '"+endingMinutesFromNow+"' minutes from now.");
		return poolId; // return poolId to the newly available SubscriptionPool
		 */
		return CandlepinTasks.createSubscriptionAndRefreshPoolsUsingRESTfulAPI(sm_serverAdminUsername, sm_serverAdminPassword, sm_serverUrl, ownerKey, 3, startingMinutesFromNow, endingMinutesFromNow, getRandInt(), getRandInt(), randomAvailableProductId, null,null).getString("id");
	}

	@AfterGroups(groups = { "setup" }, value = { "VerifyrhsmcertdRefreshIdentityCert"})
	public void restoreSystemDate() throws IOException, ParseException {
		String ClientDateAfterExecution=getDate(sm_clientHostname, sm_sshUser, sm_sshKeyPrivate,
				sm_sshkeyPassphrase,true);
		String ServerDateAfterExecution=getDate(sm_serverHostname, sm_sshUser, sm_sshKeyPrivate,
				sm_sshkeyPassphrase,true);
		String ClientDateAfterExeceutionOneDayBefore=getDate(sm_clientHostname, sm_sshUser, sm_sshKeyPrivate,
				sm_sshkeyPassphrase,false);
		String ServerDateAfterExeceutionOneDayBefore=getDate(sm_serverHostname, sm_sshUser, sm_sshKeyPrivate,
				sm_sshkeyPassphrase,false);

		if((!(ClientDateAfterExecution.equals(SystemDateOnClient))) &&(!(ClientDateAfterExeceutionOneDayBefore.equals(SystemDateOnClient))) ){

			setDate(sm_clientHostname, sm_sshUser, sm_sshKeyPrivate,
					sm_sshkeyPassphrase, "date -s '15 year ago 9 month ago'");
			log.info("Reverted the date of client"
					+ client.runCommandAndWait("hostname"));
		}

		if((!(ServerDateAfterExecution.equals(SystemDateOnServer)))&&((ServerDateAfterExeceutionOneDayBefore.equals(SystemDateOnServer)))){
			setDate(sm_serverHostname, sm_sshUser, sm_sshKeyPrivate,
					sm_sshkeyPassphrase, "date -s '15 year ago 9 month ago'");
			log.info("Reverted the date of candlepin"
					+ client.runCommandAndWait("hostname"));
		}
		clienttasks.restart_rhsmcertd(null, null, null);
		SubscriptionManagerCLITestScript.sleep(3 * 60 * 1000);
	}


	@BeforeGroups(groups = {"setup"}, value = {"VerifyrhsmcertdRefreshIdentityCert"})
	public void rgetSystemDate() throws IOException, ParseException {
		SystemDateOnClient=getDate(sm_clientHostname, sm_sshUser, sm_sshKeyPrivate,
				sm_sshkeyPassphrase,true);
		SystemDateOnServer=getDate(sm_serverHostname, sm_sshUser, sm_sshKeyPrivate,
				sm_sshkeyPassphrase,true);
	}

	@AfterGroups(groups = {"setup"}, value = {"VerifyEmptyCertCauseRegistrationFailure_Test"/*,"BugzillaTests" CAUSES THIS METHOD TO RUN AFTER THE CLASS; NOT WHAT WE WANTED*/})
	public void removeMyEmptyCaCertFile() {
		client.runCommandAndWait("rm -f "+myEmptyCaCertFile);
	}


	@AfterGroups(groups = {"setup"}, value = {/*"BugzillaTests",*/"DisplayOfRemoteServerExceptionForServer500Error","RHELWorkstationProduct"})
	public void restoreRHSMConfFileValues() {
		clienttasks.unregister(null, null, null);
		List<String[]> listOfSectionNameValues = new ArrayList<String[]>();
		listOfSectionNameValues.add(new String[] { "server","prefix".toLowerCase(), "/candlepin" });
		clienttasks.config(null, null, true, listOfSectionNameValues);
		clienttasks.register(sm_clientUsername, sm_clientPassword,
				sm_clientOrg, null, null, null, null, null, null, null,
				(String) null, null, null, null, true, null, null, null, null);
	}


	@BeforeClass(groups={"setup"})
	public void findRandomAvailableProductIdBeforeClass() throws Exception {
		clienttasks.register(sm_clientUsername, sm_clientPassword,
				sm_clientOrg, null, null, null, null, null, null, null,
				(String) null, null, null, null, true, false, null, null, null);
		List<SubscriptionPool> pools = clienttasks.getCurrentlyAvailableSubscriptionPools();
		if (!pools.isEmpty()) {
			SubscriptionPool pool = pools.get(randomGenerator.nextInt(pools.size())); 
			randomAvailableProductId = pool.productId;
		}
	}

	public static Object getJsonObjectValue (JSONObject json, String jsonName) throws JSONException, Exception {
		if (!json.has(jsonName) || json.isNull(jsonName)) return null;	
		return json.get(jsonName);
	}

	/* Do not set the server and servertasks.  They should already be set by setupBeforeSuite().
	 * If they are not set (equal to null), then you are probably testing against the stage
	 * or production server to which we cannot establish an ssh connection
	@BeforeClass(groups = "setup")
	public void SetServerTasks() throws Exception {
	server = new SSHCommandRunner(sm_serverHostname, sm_sshUser, sm_sshKeyPrivate, sm_sshkeyPassphrase, null);
	servertasks = new rhsm.cli.tasks.CandlepinTasks(server,sm_serverInstallDir,sm_serverImportDir,sm_serverType,sm_serverBranch);
	}
	 */
	
	
	
	// THE FOLLOWING BEFORE AND AFTER CLASS METHODS ARE USED TO ELIMINATE
	// THE INFLUENCE THAT /etc/pki/product-default/ CERTS HAVE ON THESE TESTS
	// SINCE THESE TESTS PRE-DATE THE INTRODUCTION OF DEFAULT PRODUCT CERTS.
	@BeforeClass(groups = "setup")
	public void backupProductDefaultCerts() {
		log.info("This test class was developed before the addition of /etc/pki/product-default/ certs (Bug 1123029).  Therefore, let's back them up before running this test class.");
		for (File productCertFile : clienttasks.getCurrentProductCertFiles()) {
			if (productCertFile.getPath().startsWith(clienttasks.productCertDefaultDir)) {
				client.runCommandAndWait("mv "+productCertFile+" "+productCertFile+".bak");
			}
		}
	}
	@AfterClass(groups = "setup")
	public void restoreProductDefaultCerts() {
		client.runCommandAndWait("ls -1 "+clienttasks.productCertDefaultDir+"/*.bak");
		String lsBakFiles = client.getStdout().trim();
		if (!lsBakFiles.isEmpty()) {
			for (String lsFile : Arrays.asList(lsBakFiles.split("\n"))) {
				client.runCommandAndWait("mv "+lsFile+" "+lsFile.replaceFirst("\\.bak$",""));
			}
		}
	}
}

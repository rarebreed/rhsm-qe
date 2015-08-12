package rhsm.cli.tests;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.LogRecord;

import org.apache.xmlrpc.XmlRpcException;
import org.json.JSONException;
import org.testng.SkipException;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterGroups;
import org.testng.annotations.BeforeGroups;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import com.redhat.qe.Assert;
import com.redhat.qe.auto.bugzilla.BlockedByBzBug;
import com.redhat.qe.auto.bugzilla.BzChecker;
import com.redhat.qe.auto.tcms.ImplementsNitrateTest;
import com.redhat.qe.auto.testng.TestNGUtils;
import com.redhat.qe.jul.TestRecords;

import rhsm.base.CandlepinType;
import rhsm.base.SubscriptionManagerCLITestScript;
import rhsm.cli.tasks.CandlepinTasks;
import rhsm.data.CertStatistics;
import rhsm.data.ConsumerCert;
import rhsm.data.ContentNamespace;
import rhsm.data.EntitlementCert;
import rhsm.data.InstalledProduct;
import rhsm.data.OrderNamespace;
import rhsm.data.ProductCert;
import rhsm.data.ProductNamespace;
import rhsm.data.Repo;
import rhsm.data.SubscriptionPool;

import com.redhat.qe.tools.RemoteFileTasks;
import com.redhat.qe.tools.SSHCommandResult;

/**
 * @author jsefler
 *
 *	back door to ALWAYS get certv3 entitlements, set fact system.testing: true
 *  http://wiki.samat.org/CheatSheet/OpenSSL
 *  
 *  <jbowes> yeah. DER size is how many bytes the cert takes up 'on the wire' in communication
 *  with the CDN subject key ID size is the size of the subject key id field, which is hacked
 *  in hosted to hold a zipped representation of the content sets for certv1. that's the field
 *  that the CDN sends from the edge server to the java app thingy that does auth so if DER size
 *  is over 120kb or so you have a problem. if subject key id size is over 28kb or so you have a problem.
 */
@Test(groups={"CertificateTests","Tier2Tests"})
public class CertificateTests extends SubscriptionManagerCLITestScript {

	// Test methods ***********************************************************************
	
	@Test(	description="Verify that no more than one RHEL product cert is ever installed.",
			groups={"AcceptanceTests","Tier1Tests","blockedByBug-854879","blockedByBug-904193"},
			enabled=true)
	//@ImplementsNitrateTest(caseId=)
	public void VerifyOnlyOneBaseRHELProductCertIsInstalled_Test() {
		
		// base RHEL product ids
		// Reference: http://git.app.eng.bos.redhat.com/git/rcm/rcm-metadata.git/tree/product_ids
		List<String> baseProductIds = Arrays.asList(
//			"180",	// Red Hat Beta
			"68",	// Red Hat Enterprise Linux Desktop
			"69",	// Red Hat Enterprise Linux Server
			"71",	// Red Hat Enterprise Linux Workstation
			"72",	// Red Hat Enterprise Linux for IBM System z
			"74",	// Red Hat Enterprise Linux for IBM POWER
			"76",	// Red Hat Enterprise Linux for Scientific Computing
			"279",	// Red Hat Enterprise Linux for IBM POWER LE
			"294");	// Red Hat Enterprise Linux Server for ARM
					// 70,73,75 are "Extended Update Support"
		
		// find all installed RHEL product Certs
		List<ProductCert> currentRhelProductCerts = new ArrayList<ProductCert>();
		List<ProductCert> currentProductCerts = clienttasks.getCurrentProductCerts();
		for (ProductCert productCert : currentProductCerts) {
			if (baseProductIds.contains(productCert.productId)) {
				currentRhelProductCerts.add(productCert);
			}
		}
// DELETEME
//		// handle RHEL 7
//		if (currentRhelProductCerts.isEmpty() && clienttasks.redhatReleaseX.equals("7")) {
//			currentRhelProductCerts = clienttasks.getCurrentProductCerts("rhel-7-.*");
//		}
//		// handle Red Hat Enterprise Linux Server for ARM
//		if (currentRhelProductCerts.isEmpty() && clienttasks.arch.equals("aarch64")) {
//			currentRhelProductCerts = clienttasks.getCurrentProductCerts("rhsa-.*");
//		}
		if (currentRhelProductCerts.size()>1) {
			log.warning("Found multiple installed RHEL product certs:");
			for (ProductCert productCert : currentRhelProductCerts) {
				log.warning(productCert.toString());
			}
		}
		if (currentRhelProductCerts.isEmpty()) {
			log.warning("Did not detect any RHEL product certs among the following installed products:");
			for (ProductCert productCert : currentProductCerts) {
				log.info(productCert.toString());
			}
		}
		/* 7/13/2015 no longer true now that /etc/pki/product-default may also provide a duplicate rhel product
		Assert.assertTrue(rhelProductCertsInstalled.size()==1,"At most only one base RHEL product cert should ever be installed.");
		instead, let's make sure they all cover the same product id... */
		Set<String> rhelProductIds = new HashSet<String>();
		for (ProductCert rhelProductCert : currentRhelProductCerts) rhelProductIds.add(rhelProductCert.productId);
		Assert.assertEquals(rhelProductIds.size(), 1, "Only one base RHEL product cert ID (actual "+rhelProductIds+") is currently installed.");
		String baseRhelProductId = (String)(rhelProductIds.toArray())[0];
		Assert.assertEquals(InstalledProduct.findAllInstancesWithMatchingFieldFromList("productId", baseRhelProductId, clienttasks.getCurrentlyInstalledProducts()).size(), 1, "Only one base RHEL product cert ID ("+baseRhelProductId+") appears in list of installed products.");
		
		Assert.assertNotNull(clienttasks.getCurrentRhelProductCert(),"Discovered the currently installed base RHEL product cert based on an expected tag.");
	}
	
	
	@Test(	description="Verify that a base product cert corresponding to the /etc/redhat-release is installed",
			groups={"AcceptanceTests","Tier1Tests","blockedByBug-706518","blockedByBug-844368","blockedByBug-1104498","blockedByBug-904193"},
			dependsOnMethods={"VerifyOnlyOneBaseRHELProductCertIsInstalled_Test"},
			enabled=true)
	//@ImplementsNitrateTest(caseId=)
	public void VerifyBaseRHELProductCertIsInstalled_Test() {

		// list the currently installed products
		clienttasks.listInstalledProducts();
		
		// list the currently installed product certs
		log.info("All installed product certs: ");
		List <ProductCert> productCerts = clienttasks.getCurrentProductCerts();
		for (ProductCert productCert : productCerts) {
			log.info(productCert.toString());
		}
		
		// assert that a product cert is installed that matches our base RHEL release version
		ProductCert rhelProductCert = null;
		if (clienttasks.releasever.equals("5Server") || clienttasks.releasever.equals("6Server")) {
			if (clienttasks.arch.startsWith("ppc")) {				// ppc ppc64
				rhelProductCert = ProductCert.findFirstInstanceWithMatchingFieldFromList("productId", "74", productCerts);	// Red Hat Enterprise Linux for IBM POWER
			}
			else if (clienttasks.arch.startsWith("s390")) {			// s390 s390x
				rhelProductCert = ProductCert.findFirstInstanceWithMatchingFieldFromList("productId", "72", productCerts);	// Red Hat Enterprise Linux for IBM System z
			}
			else { 													// i386 i686 ia64 x86_64
				rhelProductCert = ProductCert.findFirstInstanceWithMatchingFieldFromList("productId", "69", productCerts);	// Red Hat Enterprise Linux Server
			}
		}
		else if (clienttasks.releasever.equals("5Client")) {		// i386 i686 x86_64
				if (rhelProductCert==null) rhelProductCert = ProductCert.findFirstInstanceWithMatchingFieldFromList("productId", "68", productCerts);	// Red Hat Enterprise Linux Desktop
				if (rhelProductCert==null) rhelProductCert = ProductCert.findFirstInstanceWithMatchingFieldFromList("productId", "71", productCerts);	// Red Hat Enterprise Linux Workstation
		}
		else if (clienttasks.releasever.equals("6Client")) {		// i386 i686 x86_64
				rhelProductCert = ProductCert.findFirstInstanceWithMatchingFieldFromList("productId", "68", productCerts);	// Red Hat Enterprise Linux Desktop
		}
		else if (clienttasks.releasever.equals("6Workstation")) {	// i386 i686 x86_64
				rhelProductCert = ProductCert.findFirstInstanceWithMatchingFieldFromList("productId", "71", productCerts);	// Red Hat Enterprise Linux Workstation
		}
		else if (clienttasks.releasever.equals("6ComputeNode")) {	// i386 i686 x86_64
				rhelProductCert = ProductCert.findFirstInstanceWithMatchingFieldFromList("productId", "76", productCerts);	// Red Hat Enterprise Linux for Scientific Computing
		}
		
		if (clienttasks.redhatReleaseX.equals("7")) {
			rhelProductCert = clienttasks.getCurrentProductCerts("rhel-7-.*").get(0);	// should only be one (tested by VerifyOnlyOneBaseRHELProductCertIsInstalled_Test)
		}
		
		Assert.assertNotNull(rhelProductCert,"Found an installed product cert that matches the system's base RHEL release version '"+clienttasks.releasever+"' on arch '"+clienttasks.arch+"':");
		log.info(rhelProductCert.toString());
	}
	
	
	@Test(	description="candidate product cert validity dates",
			groups={"AcceptanceTests","Tier1Tests"},
			dataProvider="getProductCertFilesData",
			enabled=true)
	@ImplementsNitrateTest(caseId=64656)
	public void VerifyValidityPeriodInProductCerts_Test(File productCertFile) {
		
		ProductCert productCert = clienttasks.getProductCertFromProductCertFile(productCertFile);
		long actualValidityDurationDays = (productCert.validityNotAfter.getTimeInMillis() - productCert.validityNotBefore.getTimeInMillis())/(24*60*60*1000);
		
		log.info("Verifying the validity period in product cert '"+productCertFile+"': "+productCert);
		log.info("The validity period for this product cert is (days): "+actualValidityDurationDays);
		Calendar now = Calendar.getInstance();
		
		// verify that the validity period for this product cert has already begun.
		Assert.assertTrue(productCert.validityNotBefore.before(now),"This validity period for this product cert has already begun.");
	
		// verify that the validity period for this product cert has not yet ended.
		Assert.assertTrue(productCert.validityNotAfter.after(now),"This validity period for this product cert has not yet ended.");

		// verify that the validity period for this product cert is among the expected values.
		List<Long>expectedValidityDurationDaysList=new ArrayList<Long>();
		String productCertValidityDuration = sm_productCertValidityDuration;
		for (String expectedValidityDurationDayOption :  Arrays.asList(productCertValidityDuration.trim().split(" *, *"))) {
			expectedValidityDurationDaysList.add(Long.valueOf(expectedValidityDurationDayOption));
		}
		log.info("Asserting that the the validity period for this product certificate ("+actualValidityDurationDays+") spans one of the expected number of days ("+productCertValidityDuration+")...");
		Assert.assertContains(expectedValidityDurationDaysList, new Long(actualValidityDurationDays));
	}
	
	
	@BeforeGroups(groups={"setup"}, value={"VerifyEntitlementCertContainsExpectedOIDs_Test"})
	public void createFactsFileWithOverridingValues() {
		Map<String,String> factsMap = new HashMap<String,String>();
		factsMap.put("system.certificate_version", "1.0");
		clienttasks.createFactsFileWithOverridingValues(factsMap);
	}
	@Test(	description="Make sure the entitlement cert contains all expected OIDs",
			groups={"VerifyEntitlementCertContainsExpectedOIDs_Test","AcceptanceTests","Tier1Tests","blockedByBug-744259","blockedByBug-754426","blockedByBug-962520","blockedByBug-997970","blockedByBug-1021581"},
			dataProvider="getAllAvailableSubscriptionPoolsData",
			enabled=true)
	//@ImplementsNitrateTest(caseId=)
	public void VerifyEntitlementCertContainsExpectedOIDs_Test(SubscriptionPool pool) throws JSONException, Exception {
		
		// skip RAM-based subscriptions since they were are not supported on v1 certificates
		if (CandlepinTasks.getPoolProductAttributeValue(sm_clientUsername, sm_clientPassword, sm_serverUrl, pool.poolId, "ram")!=null) {
			throw new SkipException("This test is not designed for RAM-based product subscriptions.");
		}
		// skip CORES-based subscriptions since they were are not supported on v1 certificates
		if (CandlepinTasks.getPoolProductAttributeValue(sm_clientUsername, sm_clientPassword, sm_serverUrl, pool.poolId, "cores")!=null) {
			throw new SkipException("This test is not designed for CORES-based product subscriptions.");
		}
		// skip physical_only subscriptions when run on a virtual system
		if (CandlepinTasks.isPoolRestrictedToPhysicalSystems(sm_clientUsername, sm_clientPassword, sm_serverUrl, pool.poolId) && Boolean.valueOf(clienttasks.getFactValue("virt.is_guest"))) {
			throw new SkipException("This test is not designed for physical_only subscriptions on a virtual machine.");
		}
		// skip virt_only subscriptions when run on a physical system
		if (CandlepinTasks.isPoolRestrictedToVirtualSystems(sm_clientUsername, sm_clientPassword, sm_serverUrl, pool.poolId) && !Boolean.valueOf(clienttasks.getFactValue("virt.is_guest"))) {
			throw new SkipException("This test is not designed for virt_only subscriptions on a physical machine.");
		}
		
		// adjust quantity for instance_multiplier pools
		String instance_multiplier = CandlepinTasks.getPoolProductAttributeValue(sm_clientUsername, sm_clientPassword, sm_serverUrl, pool.poolId, "instance_multiplier");
		String quantity = null;
		/*if (clienttasks.isPackageVersion("subscription-manager",">=","1.10.3-1"))*/ if (pool.suggested!=null) {
			if (pool.suggested<1 && instance_multiplier!=null) {
				quantity = instance_multiplier;
			}
		}
		
		// subscribe to the pool and get the EntitlementCert
		//File entitlementCertFile = clienttasks.subscribeToSubscriptionPool(pool);
		//EntitlementCert entitlementCert = clienttasks.getEntitlementCertFromEntitlementCertFile(entitlementCertFile);
		//^ replaced with the following to save logging/assertion time
		SSHCommandResult result = clienttasks.subscribe_(null, null, pool.poolId, null, null, quantity, null, null, null, null, null, null);
		if (result.getStderr().startsWith("Too many content sets")) throw new SkipException("This test is only designed for system.certificate_version=1.0 compatible subscriptions.");
		//if (result.getStdout().startsWith("Pool is restricted to")) throw new SkipException("This test is not designed for this subscription pool: "+pool);	// Pool is restricted to physical systems: '8a9087e3443db08f01443db1847a142a'.
		EntitlementCert entitlementCert = clienttasks.getEntitlementCertCorrespondingToSubscribedPool(pool);
		Assert.assertNotNull(entitlementCert,"Successfully retrieved the entitlement cert granted after subscribing to pool: "+pool);
		// re-scan the entitlement cert using openssl because it better distinguishes between a "" OID value and a null OID value.
		entitlementCert = clienttasks.getEntitlementCertFromEntitlementCertFileUsingOpensslX509(entitlementCert.file);
		
		// Commented out the following log of rawCertificate to conserve logFile size
		//log.info("Raw entitlement certificate: \n"+entitlementCert.rawCertificate);
		boolean allMandatoryOIDsFound = true;
		// Reference	https://docspace.corp.redhat.com/docs/DOC-30244
		
		// asserting all expected OIDS in the OrderNamespace
		//		  1.3.6.1.4.1.2312.9.4.1 (Name): Red Hat Enterprise Linux Server
		//		  1.3.6.1.4.1.2312.9.4.2 (Order Number) : ff8080812c3a2ba8012c3a2cbe63005b  
		//		  1.3.6.1.4.1.2312.9.4.3 (SKU) : MCT0982
		//		  1.3.6.1.4.1.2312.9.4.4 (Subscription Number) : abcd-ef12-1234-5678   <- SHOULD ONLY EXIST IF ORIGINATED FROM A REGTOKEN
		//		  1.3.6.1.4.1.2312.9.4.5 (Quantity) : 100
		//		  1.3.6.1.4.1.2312.9.4.6 (Entitlement Start Date) : 2010-10-25T04:00:00Z
		//		  1.3.6.1.4.1.2312.9.4.7 (Entitlement End Date) : 2011-11-05T00:00:00Z
		//		  1.3.6.1.4.1.2312.9.4.8 (Virtualization Limit) : 4						<- ONLY EXISTS WHEN VIRT POOLS ARE TO BE GENERATED
		//		  1.3.6.1.4.1.2312.9.4.9 (Socket Limit) : None							<- ONLY EXISTS WHEN THE SUBSCRIPTION IS USED TO SATISFY HARDWARE
		//		  1.3.6.1.4.1.2312.9.4.10 (Contract Number): 152341643
		//		  1.3.6.1.4.1.2312.9.4.11 (Quantity Used): 4
		//		  1.3.6.1.4.1.2312.9.4.12 (Warning Period): 30
		//		  1.3.6.1.4.1.2312.9.4.13 (Account Number): 9876543210
		//		  1.3.6.1.4.1.2312.9.4.14 (Provides Management): 0 (boolean, 1 for true)<- NEEDINFO
		//		  1.3.6.1.4.1.2312.9.4.15 (Support Level): Premium						<- NEEDINFO
		//		  1.3.6.1.4.1.2312.9.4.16 (Support Type): Level 3						<- NEEDINFO
		//		  1.3.6.1.4.1.2312.9.4.17 (Stacking Id): 23456							<- ONLY EXISTS WHEN SUBSCRIPTION IS STACKABLE
		//		  1.3.6.1.4.1.2312.9.4.18 (Virt Only): 1								<- ONLY EXISTS WHEN POOLS ARE INTENTED FOR VIRT MACHINES ONLY
		OrderNamespace orderNamespace = entitlementCert.orderNamespace;
		if (orderNamespace.productName!=null)			{Assert.assertNotNull(orderNamespace.productName,			"Mandatory OrderNamespace OID 1.3.6.1.4.1.2312.9.4.1 (Name) is present with value '"+ orderNamespace.productName+"'");}							else {log.warning("Mandatory OrderNamespace OID 1.3.6.1.4.1.2312.9.4.1 (Name) is missing"); allMandatoryOIDsFound = false;}
		if (orderNamespace.orderNumber!=null)			{Assert.assertNotNull(orderNamespace.orderNumber,			"Mandatory OrderNamespace OID 1.3.6.1.4.1.2312.9.4.2 (Order Number) is present with value '"+ orderNamespace.orderNumber+"'");}					else {log.warning("Mandatory OrderNamespace OID 1.3.6.1.4.1.2312.9.4.2 (Order Number) is missing"); allMandatoryOIDsFound = false;}
		if (orderNamespace.productId!=null)				{Assert.assertNotNull(orderNamespace.productId,				"Mandatory OrderNamespace OID 1.3.6.1.4.1.2312.9.4.3 (SKU) is present with value '"+ orderNamespace.productId+"'");}							else {log.warning("Mandatory OrderNamespace OID 1.3.6.1.4.1.2312.9.4.3 (SKU) is missing"); allMandatoryOIDsFound = false;}
		if (orderNamespace.subscriptionNumber!=null)	{Assert.assertNotNull(orderNamespace.subscriptionNumber,	"Optional OrderNamespace OID 1.3.6.1.4.1.2312.9.4.4 (Subscription Number) is present with value '"+ orderNamespace.subscriptionNumber+"'");}	else {log.warning("Optional OrderNamespace OID 1.3.6.1.4.1.2312.9.4.4 (Subscription Number) is missing"); }
		if (orderNamespace.quantity!=null)				{Assert.assertNotNull(orderNamespace.quantity,				"Mandatory OrderNamespace OID 1.3.6.1.4.1.2312.9.4.5 (Quantity) is present with value '"+ orderNamespace.quantity+"'");}						else {log.warning("Mandatory OrderNamespace OID 1.3.6.1.4.1.2312.9.4.5 (Quantity) is missing"); allMandatoryOIDsFound = false;}
		if (orderNamespace.startDate!=null)				{Assert.assertNotNull(orderNamespace.startDate,				"Mandatory OrderNamespace OID 1.3.6.1.4.1.2312.9.4.6 (Entitlement Start Date) is present with value '"+ orderNamespace.startDate+"'");}			else {log.warning("Mandatory OrderNamespace OID 1.3.6.1.4.1.2312.9.4.6 (Entitlement Start Date) is missing"); allMandatoryOIDsFound = false;}
		if (orderNamespace.endDate!=null)				{Assert.assertNotNull(orderNamespace.endDate,				"Mandatory OrderNamespace OID 1.3.6.1.4.1.2312.9.4.7 (Entitlement End Date) is present with value '"+ orderNamespace.endDate+"'");}				else {log.warning("Mandatory OrderNamespace OID 1.3.6.1.4.1.2312.9.4.7 (Entitlement End Date) is missing"); allMandatoryOIDsFound = false;}
		if (orderNamespace.virtualizationLimit!=null)	{Assert.assertNotNull(orderNamespace.virtualizationLimit,	"Optional OrderNamespace OID 1.3.6.1.4.1.2312.9.4.8 (Virtualization Limit) is present with value '"+ orderNamespace.virtualizationLimit+"'");}	else {log.warning("Optional OrderNamespace OID 1.3.6.1.4.1.2312.9.4.8 (Virtualization Limit) is missing"); }
		if (orderNamespace.socketLimit!=null)			{Assert.assertNotNull(orderNamespace.socketLimit,			"Optional OrderNamespace OID 1.3.6.1.4.1.2312.9.4.9 (Socket Limit) is present with value '"+ orderNamespace.socketLimit+"'");}					else {log.warning("Optional OrderNamespace OID 1.3.6.1.4.1.2312.9.4.9 (Socket Limit) is missing");}
//		if (orderNamespace.contractNumber!=null)		{Assert.assertNotNull(orderNamespace.contractNumber,		"Mandatory OrderNamespace OID 1.3.6.1.4.1.2312.9.4.10 (Contract Number) is present with value '"+ orderNamespace.contractNumber+"'");}			else {log.warning("Mandatory OrderNamespace OID 1.3.6.1.4.1.2312.9.4.10 (Contract Number) is missing"); allMandatoryOIDsFound = false;}
		if (orderNamespace.contractNumber!=null)		{Assert.assertNotNull(orderNamespace.contractNumber,		"Optional OrderNamespace OID 1.3.6.1.4.1.2312.9.4.10 (Contract Number) is present with value '"+ orderNamespace.contractNumber+"'");}			else {log.warning("Optional OrderNamespace OID 1.3.6.1.4.1.2312.9.4.10 (Contract Number) is missing.  This subscription was probably granted to a test account."); }
		if (orderNamespace.quantityUsed!=null)			{Assert.assertNotNull(orderNamespace.quantityUsed,			"Mandatory OrderNamespace OID 1.3.6.1.4.1.2312.9.4.11 (Quantity Used) is present with value '"+ orderNamespace.quantityUsed+"'");}				else {log.warning("Mandatory OrderNamespace OID 1.3.6.1.4.1.2312.9.4.11 (Quantity Used) is missing"); allMandatoryOIDsFound = false;}
		if (orderNamespace.warningPeriod!=null)			{Assert.assertNotNull(orderNamespace.warningPeriod,			"Mandatory OrderNamespace OID 1.3.6.1.4.1.2312.9.4.12 (Warning Period) is present with value '"+ orderNamespace.warningPeriod+"'");}			else {log.warning("Mandatory OrderNamespace OID 1.3.6.1.4.1.2312.9.4.12 (Warning Period) is missing"); allMandatoryOIDsFound = false;}
//		if (orderNamespace.accountNumber!=null)			{Assert.assertNotNull(orderNamespace.accountNumber,			"Mandatory OrderNamespace OID 1.3.6.1.4.1.2312.9.4.13 (Account Number) is present with value '"+ orderNamespace.accountNumber+"'");}			else {log.warning("Mandatory OrderNamespace OID 1.3.6.1.4.1.2312.9.4.13 (Account Number) is missing"); allMandatoryOIDsFound = false;}
		if (orderNamespace.accountNumber!=null)			{Assert.assertNotNull(orderNamespace.accountNumber,			"Optional OrderNamespace OID 1.3.6.1.4.1.2312.9.4.13 (Account Number) is present with value '"+ orderNamespace.accountNumber+"'");}				else {log.warning("Optional OrderNamespace OID 1.3.6.1.4.1.2312.9.4.13 (Account Number) is missing.  This subscription was probably granted to a test account."); }
		if (orderNamespace.providesManagement!=null)	{Assert.assertNotNull(orderNamespace.providesManagement,	"Mandatory OrderNamespace OID 1.3.6.1.4.1.2312.9.4.14 (Provides Management) is present with value '"+ orderNamespace.providesManagement+"'");}	else {log.warning("Mandatory OrderNamespace OID 1.3.6.1.4.1.2312.9.4.14 (Provides Management) is missing"); allMandatoryOIDsFound = false;}
		if (orderNamespace.supportLevel!=null)			{Assert.assertNotNull(orderNamespace.supportLevel,			"Optional OrderNamespace OID 1.3.6.1.4.1.2312.9.4.15 (Support Level) is present with value '"+ orderNamespace.supportLevel+"'");}				else {log.warning("Optional OrderNamespace OID 1.3.6.1.4.1.2312.9.4.15 (Support Level) is missing"); }
		if (orderNamespace.supportType!=null)			{Assert.assertNotNull(orderNamespace.supportType,			"Optional OrderNamespace OID 1.3.6.1.4.1.2312.9.4.16 (Support Type) is present with value '"+ orderNamespace.supportType+"'");}					else {log.warning("Optional OrderNamespace OID 1.3.6.1.4.1.2312.9.4.16 (Support Type) is missing"); }
		if (orderNamespace.stackingId!=null)			{Assert.assertNotNull(orderNamespace.stackingId,			"Optional OrderNamespace OID 1.3.6.1.4.1.2312.9.4.17 (Stacking Id) is present with value '"+ orderNamespace.stackingId+"'");}					else {log.warning("Optional OrderNamespace OID 1.3.6.1.4.1.2312.9.4.17 (Stacking Id) is missing"); }
		if (orderNamespace.virtOnly!=null)				{Assert.assertNotNull(orderNamespace.virtOnly,				"Optional OrderNamespace OID 1.3.6.1.4.1.2312.9.4.18 (Virt Only) is present with value '"+ orderNamespace.virtOnly+"'");}						else {log.warning("Optional OrderNamespace OID 1.3.6.1.4.1.2312.9.4.18 (Virt Only) is missing"); }
		
		for (ProductNamespace productNamespace : entitlementCert.productNamespaces) {
			// asserting all expected OIDS in the ProductNamespace
			//    1.3.6.1.4.1.2312.9.1.<product_hash>.1 (Name) : Red Hat Enterprise Linux
			//    1.3.6.1.4.1.2312.9.1.<product_hash>.2 (Version) : 6.0
			//    1.3.6.1.4.1.2312.9.1.<product_hash>.3 (Architecture) : x86_64
			//    1.3.6.1.4.1.2312.9.1.<product_hash>.4 (Provides) : String1, String2, String3
			if (productNamespace.name!=null)			{Assert.assertNotNull(productNamespace.name,			"Mandatory ProductNamespace OID 1.3.6.1.4.1.2312.9.1."+productNamespace.id+".1 (Name) is present with value '"+ productNamespace.name+"'");}			else {log.warning("Mandatory ProductNamespace OID 1.3.6.1.4.1.2312.9.1."+productNamespace.id+".1 (Name) is missing"); allMandatoryOIDsFound = false;}
//			if (productNamespace.version!=null)			{Assert.assertNotNull(productNamespace.version,			"Mandatory ProductNamespace OID 1.3.6.1.4.1.2312.9.1."+productNamespace.id+".2 (Version) is present with value '"+ productNamespace.version+"'");}		else {log.warning("Mandatory ProductNamespace OID 1.3.6.1.4.1.2312.9.1."+productNamespace.id+".2 (Version) is missing"); allMandatoryOIDsFound = false;}
			if (productNamespace.version!=null)			{Assert.assertNotNull(productNamespace.version,			"Optional ProductNamespace OID 1.3.6.1.4.1.2312.9.1."+productNamespace.id+".2 (Version) is present with value '"+ productNamespace.version+"'");}		else {log.warning("Optional ProductNamespace OID 1.3.6.1.4.1.2312.9.1."+productNamespace.id+".2 (Version) is missing"); }
			if (!sm_serverType.equals(CandlepinType.standalone))	// the TESTDATA imported into a standalone candlepin does not honor the assertion that the product version OID must be blank since rel-eng creates product certs with valid version outside of candlepin
			if (productNamespace.version!=null)			{Assert.assertEquals(productNamespace.version, "",		"Optional ProductNamespace OID 1.3.6.1.4.1.2312.9.1."+productNamespace.id+".2 (Version) can be present but must always be blank.");}
			if (productNamespace.arch!=null)			{Assert.assertNotNull(productNamespace.arch,			"Mandatory ProductNamespace OID 1.3.6.1.4.1.2312.9.1."+productNamespace.id+".3 (Architecture) is present with value '"+ productNamespace.arch+"'");}	else {log.warning("Mandatory ProductNamespace OID 1.3.6.1.4.1.2312.9.1."+productNamespace.id+".3 (Architecture) is missing"); allMandatoryOIDsFound = false;}
			if (productNamespace.providedTags!=null)	{Assert.assertNotNull(productNamespace.providedTags,	"Optional ProductNamespace OID 1.3.6.1.4.1.2312.9.1."+productNamespace.id+".4 (Provides) is present with value '"+ productNamespace.providedTags+"'");}	else {log.warning("Optional ProductNamespace OID 1.3.6.1.4.1.2312.9.1."+productNamespace.id+".4 (Provides) is missing"); }
			if (productNamespace.providedTags!=null)	{Assert.assertEquals(productNamespace.providedTags, "",	"Optional ProductNamespace OID 1.3.6.1.4.1.2312.9.1."+productNamespace.id+".4 (Provides) can be present but must always be blank.");}
		}
		
		for (ContentNamespace contentNamespace : entitlementCert.contentNamespaces) {
			if (!contentNamespace.type.equalsIgnoreCase("yum")) continue;

			// asserting all expected OIDS in the ContentNamespace	
			//  1.3.6.1.4.1.2312.9.2.<content_hash>.1.1 (Name) : Red Hat Enterprise Linux (Supplementary)
			//  1.3.6.1.4.1.2312.9.2.<content_hash>.1.2 (Label) : rhel-server-6-supplementary
			//  1.3.6.1.4.1.2312.9.2.<content_hash>.1.3 (Physical Entitlements): 1
			//  1.3.6.1.4.1.2312.9.2.<content_hash>.1.4 (Flex Guest Entitlements): 0
			//  1.3.6.1.4.1.2312.9.2.<content_hash>.1.5 (Vendor ID): %Red_Hat_Id% or %Red_Hat_Label%
			//  1.3.6.1.4.1.2312.9.2.<content_hash>.1.6 (Download URL): content/rhel-server-6-supplementary/$releasever/$basearch
			//  1.3.6.1.4.1.2312.9.2.<content_hash>.1.7 (GPG Key URL): file:///etc/pki/rpm-gpg/RPM-GPG-KEY-redhat-release
			//  1.3.6.1.4.1.2312.9.2.<content_hash>.1.8 (Enabled): 1
			//  1.3.6.1.4.1.2312.9.2.<content_hash>.1.9 (Metadata Expire Seconds): 604800
			//  1.3.6.1.4.1.2312.9.2.<content_hash>.1.10 (Required Tags): TAG1,TAG2,TAG3
			if (contentNamespace.name!=null)					{Assert.assertNotNull(contentNamespace.name,					"Mandatory ContentNamespace OID 1.3.6.1.4.1.2312.9.2."+contentNamespace.hash+".1.1 (Name) is present with value '"+ contentNamespace.name+"'");}									else {log.warning("Mandatory ContentNamespace OID 1.3.6.1.4.1.2312.9.2."+contentNamespace.hash+".1.1 (Name) is missing"); allMandatoryOIDsFound = false;}
			if (contentNamespace.label!=null)					{Assert.assertNotNull(contentNamespace.label,					"Mandatory ContentNamespace OID 1.3.6.1.4.1.2312.9.2."+contentNamespace.hash+".1.2 (Label) is present with value '"+ contentNamespace.label+"'");}									else {log.warning("Mandatory ContentNamespace OID 1.3.6.1.4.1.2312.9.2."+contentNamespace.hash+".1.2 (Label) is missing"); allMandatoryOIDsFound = false;}
			//REMOVED						//if (contentNamespace.physicalEntitlement!=null)		{Assert.assertNotNull(contentNamespace.physicalEntitlement,		"Mandatory ContentNamespace OID 1.3.6.1.4.1.2312.9.2."+contentNamespace.hash+".1.3 (Physical Entitlements) is present with value '"+ contentNamespace.physicalEntitlement+"'");}	else {log.warning("Mandatory ContentNamespace OID 1.3.6.1.4.1.2312.9.2."+contentNamespace.hash+".1.3 (Physical Entitlements) is missing"); allMandatoryOIDsFound = false;}
			Assert.assertNull(contentNamespace.physicalEntitlement,		"ContentNamespace OID 1.3.6.1.4.1.2312.9.2."+contentNamespace.hash+".1.3 (Physical Entitlements) has been removed from the generation of entitlements.  Its current value is '"+ contentNamespace.physicalEntitlement+"'");
			//REMOVED blockedByBug-754426	//if (contentNamespace.flexGuestEntitlement!=null)	{Assert.assertNotNull(contentNamespace.flexGuestEntitlement,	"Mandatory ContentNamespace OID 1.3.6.1.4.1.2312.9.2."+contentNamespace.hash+".1.4 (Flex Guest Entitlements) is present with value '"+ contentNamespace.flexGuestEntitlement+"'");}	else {log.warning("Mandatory ContentNamespace OID 1.3.6.1.4.1.2312.9.2."+contentNamespace.hash+".1.4 (Flex Guest Entitlements) is missing"); allMandatoryOIDsFound = false;}
			Assert.assertNull(contentNamespace.flexGuestEntitlement,	"ContentNamespace OID 1.3.6.1.4.1.2312.9.2."+contentNamespace.hash+".1.4 (Flex Guest Entitlements) has been removed from the generation of entitlements.  Its current value is '"+ contentNamespace.flexGuestEntitlement+"'");
			if (contentNamespace.vendorId!=null)				{Assert.assertNotNull(contentNamespace.vendorId,				"Mandatory ContentNamespace OID 1.3.6.1.4.1.2312.9.2."+contentNamespace.hash+".1.5 (Vendor ID) is present with value '"+ contentNamespace.vendorId+"'");}							else {log.warning("Mandatory ContentNamespace OID 1.3.6.1.4.1.2312.9.2."+contentNamespace.hash+".1.5 (Vendor ID) is missing"); allMandatoryOIDsFound = false;}
			if (contentNamespace.downloadUrl!=null)				{Assert.assertNotNull(contentNamespace.downloadUrl,				"Mandatory ContentNamespace OID 1.3.6.1.4.1.2312.9.2."+contentNamespace.hash+".1.6 (Download URL) is present with value '"+ contentNamespace.downloadUrl+"'");}						else {log.warning("Mandatory ContentNamespace OID 1.3.6.1.4.1.2312.9.2."+contentNamespace.hash+".1.6 (Download URL) is missing"); allMandatoryOIDsFound = false;}
//			if (contentNamespace.gpgKeyUrl!=null)				{Assert.assertNotNull(contentNamespace.gpgKeyUrl,				"Mandatory ContentNamespace OID 1.3.6.1.4.1.2312.9.2."+contentNamespace.hash+".1.7 (GPG Key URL) is present with value '"+ contentNamespace.gpgKeyUrl+"'");}						else {log.warning("Mandatory ContentNamespace OID 1.3.6.1.4.1.2312.9.2."+contentNamespace.hash+".1.7 (GPG Key URL) is missing"); allMandatoryOIDsFound = false;}
//			if (contentNamespace.gpgKeyUrl!=null)				{Assert.assertNotNull(contentNamespace.gpgKeyUrl,				"Mandatory ContentNamespace OID 1.3.6.1.4.1.2312.9.2."+contentNamespace.hash+".1.7 (GPG Key URL) is present with value '"+ contentNamespace.gpgKeyUrl+"'");}						else {if ("content-emptygpg".equals(contentNamespace.name)||"content-nogpg".equals(contentNamespace.name)) {log.info("Skipping assertion of Mandatory ContentNamespace OID 1.3.6.1.4.1.2312.9.2."+contentNamespace.hash+".1.7 (GPG Key URL) for negative test content '"+contentNamespace.name+"'");} else {log.warning("Mandatory ContentNamespace OID 1.3.6.1.4.1.2312.9.2."+contentNamespace.hash+".1.7 (GPG Key URL) is missing"); allMandatoryOIDsFound = false;}}
			if (contentNamespace.gpgKeyUrl!=null)				{Assert.assertNotNull(contentNamespace.gpgKeyUrl,				"Optional ContentNamespace OID 1.3.6.1.4.1.2312.9.2."+contentNamespace.hash+".1.7 (GPG Key URL) is present with value '"+ contentNamespace.gpgKeyUrl+"'");}							else {log.warning("Optional ContentNamespace OID 1.3.6.1.4.1.2312.9.2."+contentNamespace.hash+".1.7 (GPG Key URL) is missing"); }
			if (contentNamespace.enabled!=null)					{Assert.assertNotNull(contentNamespace.enabled,					"Mandatory ContentNamespace OID 1.3.6.1.4.1.2312.9.2."+contentNamespace.hash+".1.8 (Enabled) is present with value '"+ contentNamespace.enabled+"'");}								else {log.warning("Mandatory ContentNamespace OID 1.3.6.1.4.1.2312.9.2."+contentNamespace.hash+".1.8 (Enabled) is missing"); allMandatoryOIDsFound = false;}
			if (contentNamespace.metadataExpire!=null)			{Assert.assertNotNull(contentNamespace.metadataExpire,			"Optional ContentNamespace OID 1.3.6.1.4.1.2312.9.2."+contentNamespace.hash+".1.9 (Metadata Expire Seconds) is present with value '"+ contentNamespace.metadataExpire+"'");}		else {log.warning("Optional ContentNamespace OID 1.3.6.1.4.1.2312.9.2."+contentNamespace.hash+".1.9 (Metadata Expire Seconds) is missing"); }
			if (contentNamespace.requiredTags!=null)			{Assert.assertNotNull(contentNamespace.requiredTags,			"Optional ContentNamespace OID 1.3.6.1.4.1.2312.9.2."+contentNamespace.hash+".1.10 (Required Tags) is present with value '"+ contentNamespace.requiredTags+"'");}					else {log.warning("Optional ContentNamespace OID 1.3.6.1.4.1.2312.9.2."+contentNamespace.hash+".1.10 (Required Tags) is missing"); }
		}
		
		if (!allMandatoryOIDsFound) Assert.fail("Could not find all mandatory entitlement cert OIDs. (see warnings above)");
	}
	@AfterGroups(groups={"setup"}, value={"VerifyEntitlementCertContainsExpectedOIDs_Test"})
	@AfterClass(groups={"setup"})	// insurance; not really needed
	public void deleteFactsFileWithOverridingValues() {
		clienttasks.deleteFactsFileWithOverridingValues();
	}
	
	
	@Test(	description="assert that the rct cat-cert tool reports the currently installed product certs are Certificate: Version: 1.0 (Note: this is not the ProductNamespace.version)",
			groups={},
			enabled=true)
	//@ImplementsNitrateTest(caseId=)
	public void VerifyProductCertsAreV1Certificates_Test() {

		/* installed product certs only
		List <ProductCert> productCerts = clienttasks.getCurrentProductCerts();
		if (productCerts.isEmpty()) throw new SkipException("No testable product certs are currently installed."); 
		for (ProductCert productCert : productCerts) {
			Assert.assertEquals(productCert.version, "1.0", "The rct cat-cert tool reports this product cert to be a V1 Certificate: "+productCert);
		}
		*/
		
		/* installed product certs and migration product certs */
		List<List<Object>> productCertFilesData = getProductCertFilesDataAsListOfLists();
		if (productCertFilesData.isEmpty()) throw new SkipException("No testable product certs are available for this test."); 
		for (List<Object> productCertFilesDatum : productCertFilesData) {
			File productCertFile = (File) productCertFilesDatum.get(0);
			ProductCert productCert = clienttasks.getProductCertFromProductCertFile(productCertFile);
			Assert.assertEquals(productCert.version, "1.0", "The rct cat-cert tool reports this product cert to be a V1 Certificate: "+productCert);
		}
	}
	
	
	@Test(	description="assert that the rct cat-cert tool reports the current consumer cert is a Certificate: Version: 1.0",
			groups={"blockedByBug-863961"},
			enabled=true)
	//@ImplementsNitrateTest(caseId=)
	public void VerifyConsumerCertsAreV1Certificates_Test() {
		
		clienttasks.register(sm_clientUsername, sm_clientPassword, sm_clientOrg, null, null, null, null, null, null, null, (String)null, null, null, null, true, null, null, null, null);
		ConsumerCert consumerCert = clienttasks.getCurrentConsumerCert();
		Assert.assertEquals(consumerCert.version, "1.0", "The rct cat-cert tool reports this consumer cert to be a V1 Certificate: "+consumerCert);
	}
	
	
	@Test(	description="assert that the rct cat-cert tool reports the issuer of consumer/entitlement/product certificates",
			groups={"AcceptanceTests","Tier1Tests","blockedByBug-968364"},
			enabled=true)
	//@ImplementsNitrateTest(caseId=)
	public void VerifyIssuerOfConsumerProductAndEntitlementCerts_Test() throws JSONException, Exception {
		
		clienttasks.register(sm_clientUsername, sm_clientPassword, sm_clientOrg, null, null, null, null, null, null, null, (String)null, null, null, null, true, false, null, null, null);
		ConsumerCert consumerCert = clienttasks.getCurrentConsumerCert();
		
		// assert the issuer of the consumer cert
		Assert.assertNotNull(consumerCert.issuer, "The rct cat-cert tool reports the issuer of the current consumer cert: "+consumerCert);
		if (sm_serverType.equals(CandlepinType.hosted)) Assert.assertEquals(consumerCert.issuer, "Red Hat Candlepin Authority", "Issuer of the current consumer cert: "+consumerCert.file);
		else if (sm_serverType.equals(CandlepinType.standalone)) Assert.assertEquals(consumerCert.issuer, sm_serverHostname, "Issuer of the current consumer cert: "+consumerCert.file);
		else log.warning("Do not know what value to assert for issuer of the current consumer cert from a candlepin type '"+sm_serverType+"'.");
		consumerCert=null;
		
		// assert the issuer of a redhat product cert
		for (ProductCert productCert : clienttasks.getCurrentProductCerts()) {
			Assert.assertNotNull(productCert.issuer, "The rct cat-cert tool reports the issuer of the installed product cert: "+productCert);
			if (!productCert.file.getPath().endsWith("_.pem")) Assert.assertEquals(productCert.issuer, "Red Hat Entitlement Product Authority", "Issuer of the current installed product cert: "+productCert.file);
		}
		
		// assert the issuer of an entitlement cert
		List<EntitlementCert> entitlementCerts = clienttasks.getCurrentEntitlementCerts();
		if (entitlementCerts.isEmpty()) {
			List<SubscriptionPool> pools = clienttasks.getCurrentlyAvailableSubscriptionPools();
			if (pools.isEmpty()) {
				log.warning("Cound not find any available pool.");
				// TEMPORARY WORKAROUND
				if (clienttasks.arch.equals("ppc64le")) {
					boolean invokeWorkaroundWhileBugIsOpen = true;
					String bugId="1156638"; // Bug 1156638 - "Red Hat Enterprise Linux for IBM POWER" subscriptions need to provide content for arch "ppc64le"
					try {if (invokeWorkaroundWhileBugIsOpen&&BzChecker.getInstance().isBugOpen(bugId)) {log.fine("Invoking workaround for "+BzChecker.getInstance().getBugState(bugId).toString()+" Bugzilla "+bugId+".  (https://bugzilla.redhat.com/show_bug.cgi?id="+bugId+")");SubscriptionManagerCLITestScript.addInvokedWorkaround(bugId);} else {invokeWorkaroundWhileBugIsOpen=false;}} catch (XmlRpcException xre) {/* ignore exception */} catch (RuntimeException re) {/* ignore exception */}
					if (invokeWorkaroundWhileBugIsOpen) {
						throw new SkipException("Skipping the remainder of this test on arch '"+clienttasks.arch+"' while blocking bug '"+bugId+"' is open.");
					}
				}
				// END OF WORKAROUND
				Assert.fail("Expected at least one available pool.  Maybe all subscriptions available to '"+sm_clientUsername+"' are being utilized.  Maybe all the available pools for this consumer's organization '"+clienttasks.getCurrentlyRegisteredOwnerKey()+"' do not support this systems arch '"+clienttasks.arch+"'.");
			}
			SubscriptionPool pool = getRandomListItem(pools);	// randomly pick a pool
			clienttasks.subscribeToSubscriptionPool(pool);
			entitlementCerts = clienttasks.getCurrentEntitlementCerts();
		}
		for (EntitlementCert entitlementCert : entitlementCerts) {
			Assert.assertNotNull(entitlementCert.issuer, "The rct cat-cert tool reports the issuer of granted entitlement cert: "+entitlementCert);
			if (sm_serverType.equals(CandlepinType.hosted)) Assert.assertEquals(entitlementCert.issuer, "Red Hat Candlepin Authority", "Issuer of granted entitlement cert: "+entitlementCert.file);
			else if (sm_serverType.equals(CandlepinType.standalone)) Assert.assertEquals(entitlementCert.issuer, sm_serverHostname, "Issuer of granted entitlement cert: "+entitlementCert.file);
			else log.warning("Do not know what value to assert for issuer of an entitlement cert from a candlepin type '"+sm_serverType+"'.");
		}

	}
	
	
	@Test(	description="assert that the rct cat-cert tool reports orders as Unlimited instead of -1",
			groups={"AcceptanceTests","Tier1Tests","blockedByBug-1011961"},
			enabled=true)
	//@ImplementsNitrateTest(caseId=)
	public void VerifyRctCatCertReportsOrdersWithQuantityUnlimited_Test() throws JSONException, Exception {
		int numberOfUnlimitedPools = 0;
		boolean isSystemVirtual = Boolean.valueOf(clienttasks.getFactValue("virt.is_guest"));
		clienttasks.register(sm_clientUsername, sm_clientPassword, sm_clientOrg, null, null, null, null, null, null, null, (String)null, null, null, null, true, null, null, null, null);
		clienttasks.autoheal(null, null, true, null, null, null);
		for (SubscriptionPool pool : clienttasks.getCurrentlyAllAvailableSubscriptionPools()) {
			if (pool.quantity.equalsIgnoreCase("Unlimited")) {
				
				// skip physical_only subscriptions when run on a virtual system (not necessary if getCurrentlyAllAvailableSubscriptionPools() was changed to getCurrentlyAvailableSubscriptionPools())
				if (CandlepinTasks.isPoolRestrictedToPhysicalSystems(sm_clientUsername, sm_clientPassword, sm_serverUrl, pool.poolId) && isSystemVirtual) continue;
				
				// skip virt_only subscriptions when run on a physical system (not necessary if getCurrentlyAllAvailableSubscriptionPools() was changed to getCurrentlyAvailableSubscriptionPools())
				if (CandlepinTasks.isPoolRestrictedToVirtualSystems(sm_clientUsername, sm_clientPassword, sm_serverUrl, pool.poolId) && !isSystemVirtual) continue;
				
				numberOfUnlimitedPools++;
				File entitlementCertFile = clienttasks.subscribeToSubscriptionPool(pool,/*sm_serverAdminUsername*/sm_clientUsername,/*sm_serverAdminPassword*/sm_clientPassword,sm_serverUrl);
				EntitlementCert entitlementCert = clienttasks.getEntitlementCertFromEntitlementCertFile(entitlementCertFile);
				Assert.assertEquals(entitlementCert.orderNamespace.quantity, pool.quantity, "The Order:Quantity from the granted entitlement should match the Quantity from the available Subscription Pool: "+pool);
			} else if (pool.quantity.equals("-1")) {
				Assert.fail("Available SubscriptionPools should NOT report a quantity of '"+pool.quantity+"': "+pool);
			}
		}
		if (numberOfUnlimitedPools==0) throw new SkipException("Could not find any available pools with an unlimited quantity for this test.");
	}
	
	
	@Test(	description="assert the statistic values reported by the rct stat-cert tool for the current consumer cert",
			groups={},
			enabled=true)
	//@ImplementsNitrateTest(caseId=)
	public void AssertConsumerCertStatistics_Test() {
		
		clienttasks.register(sm_clientUsername, sm_clientPassword, sm_clientOrg, null, null, null, null, null, null, null, (String)null, null, null, null, false, null, null, null, null);
		ConsumerCert consumerCert = clienttasks.getCurrentConsumerCert();
		Assert.assertEquals(consumerCert.version, "1.0", "The rct cat-cert tool reports this consumer cert to be a V1 Certificate: "+consumerCert);
		CertStatistics certStatistics = clienttasks.getCertStatisticsFromCertFile(consumerCert.file);
		
		//	[root@jsefler-6 ~]# rct stat-cert /etc/pki/consumer/cert.pem 
		//	Type: Identity Certificate
		//	Version: 1.0
		//	DER size: 925b
		//	Subject Key ID size: 20b

		String expectedDerSize = client.runCommandAndWait("openssl x509 -in "+consumerCert.file.getPath()+" -outform der -out /tmp/cert.der && du -b /tmp/cert.der | cut -f 1").getStdout().trim();
		expectedDerSize+="b";
		
		Assert.assertEquals(certStatistics.type, "Identity Certificate","rct stat-cert reports this Type.");
		Assert.assertEquals(certStatistics.version, consumerCert.version,"rct stat-cert reports this Version.");
		Assert.assertEquals(certStatistics.derSize, expectedDerSize, "rct stat-cert reports this DER size.");
		Assert.assertNotNull(certStatistics.subjectKeyIdSize, "rct stat-cert reports this Subject Key ID size.");	// TODO assert something better than not null
		Assert.assertNull(certStatistics.contentSets, "rct stat-cert does NOT report a number of Content sets.");
	}
	
	
	@Test(	description="assert the statistic values reported by the rct stat-cert tool for currently installed product certs",
			groups={},
			enabled=true)
	//@ImplementsNitrateTest(caseId=)
	public void AssertProductCertStatistics_Test() {
		
		// get all the product certs on the system
		List<ProductCert> productCerts = new ArrayList();
		List<ProductCert> installedProductCerts = clienttasks.getCurrentProductCerts();
		List<ProductCert> migrationProductCerts = clienttasks.getProductCerts("/usr/share/rhsm/product/RHEL-"+clienttasks.redhatReleaseX);
		productCerts.addAll(installedProductCerts);
		productCerts.addAll(migrationProductCerts);
		
		// loop through all of the current product certs
		if (productCerts.isEmpty()) throw new SkipException("There are currently no installed product certs to assert statistics.");
		for (ProductCert productCert : productCerts) {
			CertStatistics certStatistics = clienttasks.getCertStatisticsFromCertFile(productCert.file);
			if (productCert.file.toString().endsWith("_.pem")) continue; 	// skip the generated TESTDATA productCerts
			
			//	[root@jsefler-6 ~]# rct stat-cert /etc/pki/product/69.pem 
			//	Type: Product Certificate
			//	Version: 1.0
			//	DER size: 1553b
			
			String expectedDerSize = client.runCommandAndWait("openssl x509 -in "+productCert.file.getPath()+" -outform der -out /tmp/cert.der && du -b /tmp/cert.der | cut -f 1").getStdout().trim();
			expectedDerSize+="b";
			
			Assert.assertEquals(certStatistics.type, "Product Certificate","rct stat-cert reports this Type.");
			Assert.assertEquals(certStatistics.version, productCert.version,"rct stat-cert reports this Version.");
			Assert.assertEquals(certStatistics.derSize, expectedDerSize, "rct stat-cert reports this DER size.");
			Assert.assertNull(certStatistics.subjectKeyIdSize, "rct stat-cert does NOT report a Subject Key ID size.");
			Assert.assertNull(certStatistics.contentSets, "rct stat-cert does NOT report a number of Content sets.");
		}
	}
	
	
	@Test(	description="assert the statistic values reported by the rct stat-cert tool for currently subscribed entitlements",
			groups={"AcceptanceTests","Tier1Tests"},
			enabled=true)
	//@ImplementsNitrateTest(caseId=)
	public void AssertEntitlementCertStatistics_Test() {
		
		clienttasks.register(sm_clientUsername, sm_clientPassword, sm_clientOrg, null, null, null, null, null, null, null, (String)null, null, null, null, false, null, null, null, null);

		// get some entitlements!
		clienttasks.subscribeToTheCurrentlyAllAvailableSubscriptionPoolsCollectively();
		List<EntitlementCert> entitlementCerts = clienttasks.getCurrentEntitlementCerts();
		
		// loop through all of the current entitlement certs
		if (entitlementCerts.isEmpty()) throw new SkipException("There are currently no entitlement certs to assert statistics.");
		for (EntitlementCert entitlementCert : entitlementCerts) {
			CertStatistics certStatistics = clienttasks.getCertStatisticsFromCertFile(entitlementCert.file);
			
			//	[root@jsefler-6 ~]# rct stat-cert /etc/pki/entitlement/5254857399115244164.pem 
			//	Type: Entitlement Certificate
			//	Version: 3.0
			//	DER size: 947b
			//	Subject Key ID size: 20b
			//	Content sets: 3

			String expectedDerSize = client.runCommandAndWait("openssl x509 -in "+entitlementCert.file.getPath()+" -outform der -out /tmp/cert.der && du -b /tmp/cert.der | cut -f 1").getStdout().trim();
			expectedDerSize+="b";
			
			Assert.assertEquals(certStatistics.type, "Entitlement Certificate","rct stat-cert reports this Type.");
			Assert.assertEquals(certStatistics.version, entitlementCert.version,"rct stat-cert reports this Version.");
			Assert.assertEquals(certStatistics.derSize, expectedDerSize, "rct stat-cert reports this DER size.");
			Assert.assertNotNull(certStatistics.subjectKeyIdSize, "rct stat-cert reports this Subject Key ID size.");	// TODO assert something better than not null
			Assert.assertEquals(certStatistics.contentSets, Integer.valueOf(entitlementCert.contentNamespaces.size()), "rct stat-cert reports this number of Content sets.");
		}
	}
	
	
	@Test(	description="assert the statistic values reported by the rct stat-cert tool for a zero-content set entitlement",
			groups={"blockedByBug-966137"},
			enabled=true)
	//@ImplementsNitrateTest(caseId=)
	public void AssertEntitlementCertStatisticsForZeroContentSetEntitlement_Test() {
		File zeroContentSetEntitlementCertFile = new File("/tmp/zeroContentSetEntitlementCert.pem");
		client.runCommandAndWait("echo \""+zeroContentSetEntitlementCert.trim()+"\" > "+zeroContentSetEntitlementCertFile);
		
		EntitlementCert entitlementCert = clienttasks.getEntitlementCertFromEntitlementCertFile(zeroContentSetEntitlementCertFile);
		//	[root@jsefler-5 tmp]# rct cat-cert /tmp/8a85f9843ad21fe4013ae6a01d0b5d3c.pem 
		//
		//	+-------------------------------------------+
		//		Entitlement Certificate
		//	+-------------------------------------------+
		//
		//	Certificate:
		//		Path: /tmp/8a85f9843ad21fe4013ae6a01d0b5d3c.pem
		//		Version: 3.0
		//		Serial: 8429484359950764937
		//		Start Date: 2012-11-09 05:00:00+00:00
		//		End Date: 2013-11-09 04:59:59+00:00
		//		Pool ID: Not Available
		//
		//	Subject:
		//		CN: 8a85f9843e139e8c013e1e24d82f1ed0
		//
		//	Issuer:
		//		C: US
		//		CN: Red Hat Candlepin Authority
		//		O: Red Hat, Inc.
		//		OU: Red Hat Network
		//		ST: North Carolina
		//		emailAddress: ca-support@redhat.com
		Assert.assertEquals(entitlementCert.id, "8a85f9843e139e8c013e1e24d82f1ed0","rct cat-cert reports this id.");
		Assert.assertEquals(entitlementCert.issuer, "Red Hat Candlepin Authority","rct cat-cert reports this issuer.");
		Assert.assertEquals(entitlementCert.contentNamespaces.size(), 0, "rct cat-cert reports this number of Content sets.");


		CertStatistics certStatistics = clienttasks.getCertStatisticsFromCertFile(zeroContentSetEntitlementCertFile);
		//	[root@jsefler-5 tmp]# rct stat-cert /tmp/8a85f9843ad21fe4013ae6a01d0b5d3c.pem 
		//	Type: Entitlement Certificate
		//	Version: 3.0
		//	DER size: 1891b
		//	Subject Key ID size: 20b
		//	Content sets: 0
		Assert.assertEquals(certStatistics.type, "Entitlement Certificate","rct stat-cert reports this Type.");
		Assert.assertEquals(certStatistics.version, entitlementCert.version,"rct stat-cert reports this Version.");
		Assert.assertEquals(certStatistics.derSize, "1891b", "rct stat-cert reports this DER size.");
		Assert.assertEquals(certStatistics.subjectKeyIdSize, "20b", "rct stat-cert reports this Subject Key ID size.");	// TODO assert something better than not null
		Assert.assertEquals(certStatistics.contentSets, Integer.valueOf(0), "rct stat-cert reports this number of Content sets.");
	}
	protected static final String zeroContentSetEntitlementCert = 
		"-----BEGIN CERTIFICATE-----"+"\n"+
		"MIIHXzCCBUegAwIBAgIIdPuLXTKTi4kwDQYJKoZIhvcNAQEFBQAwgaQxCzAJBgNV"+"\n"+
		"BAYTAlVTMRcwFQYDVQQIDA5Ob3J0aCBDYXJvbGluYTEWMBQGA1UECgwNUmVkIEhh"+"\n"+
		"dCwgSW5jLjEYMBYGA1UECwwPUmVkIEhhdCBOZXR3b3JrMSQwIgYDVQQDDBtSZWQg"+"\n"+
		"SGF0IENhbmRsZXBpbiBBdXRob3JpdHkxJDAiBgkqhkiG9w0BCQEWFWNhLXN1cHBv"+"\n"+
		"cnRAcmVkaGF0LmNvbTAeFw0xMjExMDkwNTAwMDBaFw0xMzExMDkwNDU5NTlaMCsx"+"\n"+
		"KTAnBgNVBAMTIDhhODVmOTg0M2UxMzllOGMwMTNlMWUyNGQ4MmYxZWQwMIIBIjAN"+"\n"+
		"BgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA77qX2x+5FMhbEnVo2jqwKYzyVqcE"+"\n"+
		"pyV1u0smHoNiTOfGJrLSC6lpxzINlOiKns689DGeWgcCR8wyddSYeeoZQezcdXsL"+"\n"+
		"hLpLcyYDZO1Iull0ZYCsu9o7KVtckJoCzChe6SzUp18rF0OMTxiAPbxv+EHw6bre"+"\n"+
		"YS86JHZinz4Nf8pHb3sfckFLqZTTRvZ5ctCetfBLAoArQU1P7DP2cB0ShdKEqN0q"+"\n"+
		"T2V/QCeoR3TFsypgJWCFrxCkMpUfDymQq/z+vFFJlUz2q5sEWFVnY9nAYJysZtFm"+"\n"+
		"3Il4IGruvvp/yerQMkuVACRdiscd5p5frmmysNmZQm1WJUHQgPA/2prfAQIDAQAB"+"\n"+
		"o4IDCzCCAwcwEQYJYIZIAYb4QgEBBAQDAgWgMAsGA1UdDwQEAwIEsDCB3gYDVR0j"+"\n"+
		"BIHWMIHTgBR3LqXNNw2o4dPqYcVWZ0PokcdtHKGBt6SBtDCBsTELMAkGA1UEBhMC"+"\n"+
		"VVMxFzAVBgNVBAgMDk5vcnRoIENhcm9saW5hMRYwFAYDVQQKDA1SZWQgSGF0LCBJ"+"\n"+
		"bmMuMRgwFgYDVQQLDA9SZWQgSGF0IE5ldHdvcmsxMTAvBgNVBAMMKFJlZCBIYXQg"+"\n"+
		"RW50aXRsZW1lbnQgT3BlcmF0aW9ucyBBdXRob3JpdHkxJDAiBgkqhkiG9w0BCQEW"+"\n"+
		"FWNhLXN1cHBvcnRAcmVkaGF0LmNvbYIBPzAdBgNVHQ4EFgQUH4sIAAAAAAAAAAMA"+"\n"+
		"AAAAAAAAAAAwEwYDVR0lBAwwCgYIKwYBBQUHAwIwEgYJKwYBBAGSCAkGBAUMAzMu"+"\n"+
		"MDCCAboGCSsGAQQBkggJBwSCAasEggGneNodj1FuxDAIROcw+5uPtmpvUanqngDb"+"\n"+
		"bGLVMRYQr/b2JfmDYRgeuVXuDt0SEjuBD0MTKoka9cyKp+ifOXmVjizdT/cH3rHr"+"\n"+
		"umgo+zhCk8Io1Rx2JMtax+lfyIx92anTGknb6Lj//nzfMVTKkb3O6i+8YTrkWqCG"+"\n"+
		"Ic+w5sfiIs2CixuMdYYY9VwiKQAKz2vOji98wnLQpsaP2the5rwHxxiN9zCTvqBs"+"\n"+
		"9XrUXDQisNV1o0k11mIQFLdExqR5w025cTTnycLpWFFNIAaTQzPjH6W0bg0z3Ll8"+"\n"+
		"OX35YDK6Acp3nb+jxnS5rsrY6mbu5wvir1K9nrzuurPb6b48qfoU7qhznR4f6zpc"+"\n"+
		"13HUznSz4996J849etV0A5TvO39Hj05nS5rsrY6mboZwraRuXpFtGsV8JaxWzqr6"+"\n"+
		"tpH1bb/W9fMXoVbSL62BXflh4vjldIOU7zo8Ln3ddlbHUznBur0eoc50eFz7uu46"+"\n"+
		"mc6N1e/1LiVWG5adWEy9XldIOU7zo8LmuytjqZzg3V9sv/V9vL+ZbLLtxd/VDg8L"+"\n"+
		"iVXbg8UODwuJVVDhcSqgMA0GCSqGSIb3DQEBBQUAA4ICAQAAyGTZEj3O/lMWMSxA"+"\n"+
		"luoweBactyg1dNm0XgJBzNoZTpQ8zgPBeqU8lJAp4tuwpWHsZG3nK7L5BcPjYl5n"+"\n"+
		"lwKJi82aCEqi3kNaCVFjO9l6UgtLwv56zKxELF/YRHcX8gsVbJPH+UlZ23vqS3MM"+"\n"+
		"lZWFpZK+clmT8ILp+BR+wKz1jpgEXT4Jndjk1pUVuQ7hOJHOLyAsz5j6uGuCTftA"+"\n"+
		"FPxmrIqxbAS3aUInIsBr87n1LjIxwLtest8TxeuVYbdO05cwn9V9ezvP4hBYcZB1"+"\n"+
		"kmigkGzH8sq3YmJWXVynRK48fPr1SZEP6B5Ht1w3VAiAPyQ+mgBUExAbUYch/QJw"+"\n"+
		"N2Q8oemMDWMtPYO66viP09fU1YFfsd3zC7UJNt1v8MJN1f912ppZqGqPwqNmwY/F"+"\n"+
		"zKNfyMj+aL7/UoE/zET1Z5FWAviUjrazWG+j1bJpyjlmgLvaA9H0kMbxEYtbwVJZ"+"\n"+
		"cYZuk530Wk0kbJOpglrGcSmWw9aH8PXrqrEPrUDiDBdORirLs47t6uae5WozTU3k"+"\n"+
		"epJZox2Cf0stOCVZG3az43AR3ELnd808cJQZ3tJ8TvwcxYASjIlmr18IyDuxF3Zq"+"\n"+
		"F9K/67fgw7B/EhPCXgfAnN7Di0jAk40OXjtMMnwDro38biD9+GlMn0tff9wqwKZP"+"\n"+
		"gbNTs6MdV3TLHF7zcOhksJiALQ=="+"\n"+
		"-----END CERTIFICATE-----"+"\n"+
		"-----BEGIN RSA PRIVATE KEY-----"+"\n"+
		"MIIEpQIBAAKCAQEA77qX2x+5FMhbEnVo2jqwKYzyVqcEpyV1u0smHoNiTOfGJrLS"+"\n"+
		"C6lpxzINlOiKns689DGeWgcCR8wyddSYeeoZQezcdXsLhLpLcyYDZO1Iull0ZYCs"+"\n"+
		"u9o7KVtckJoCzChe6SzUp18rF0OMTxiAPbxv+EHw6breYS86JHZinz4Nf8pHb3sf"+"\n"+
		"ckFLqZTTRvZ5ctCetfBLAoArQU1P7DP2cB0ShdKEqN0qT2V/QCeoR3TFsypgJWCF"+"\n"+
		"rxCkMpUfDymQq/z+vFFJlUz2q5sEWFVnY9nAYJysZtFm3Il4IGruvvp/yerQMkuV"+"\n"+
		"ACRdiscd5p5frmmysNmZQm1WJUHQgPA/2prfAQIDAQABAoIBAQDEJvFKIlLWZnne"+"\n"+
		"SKNarNqnpORFcrOj8Eq8zWiLXwV3182SrI6hbCVZD+N9DCkgeBiz1kWzdDixdqtz"+"\n"+
		"Llj0qE+yWZSpq9xo5eYGefwdAOqZT8ilpPuxWYc+97pttxbC5eWC9WOq73vatTKB"+"\n"+
		"JZMI8L7Na2csV/LNok74tsVLdwKko9EPTdrMdyrxwlKcqjUIuryjV0xIm0BttGC5"+"\n"+
		"ocAVW2V4KTdHeq3JKme2qle51RBWciluN1V2JdnE1sD7tTUkScbinCOO8iapMZBJ"+"\n"+
		"RslzHbhtu39UkMcDY+wDjKT2ZIv5jYKF6TV+r27oDGZ2ulbwiWJtS/T5AR1ab/Ca"+"\n"+
		"8QqtCx6BAoGBAPrxu80ys4HKnP7dkUedQeH1e+1PA7LCkHWUsIkQF6n7IOU2Oo3Y"+"\n"+
		"42u9K5QIJ8cR74L/L8sCGc63DpwofgslixjinA1Qbk0t5nbOdru8+yW8hCSzn6HJ"+"\n"+
		"7crygKfxpMZ1nk1mGssl723bm0Ybl2/htaJCcPA3kiLNO5wz78VrVQXFAoGBAPSP"+"\n"+
		"A+dgWe1FOIGAhDyNVn5Hcaf8F1VuLXz45n0dSIlBVWRmXcOq6AtVX7DtszlQXahA"+"\n"+
		"bKxASuUDhi2RllIwLLeeCa+1UUmJgWgsYW0pLyI+aQ1yksKSAItEe7803aMv5Rsq"+"\n"+
		"spYaWHdYHcHbI+W0B6bp2kUvajylTJ2U/FOkEIQNAoGBAKSUgh0UUZKgNdMZsbyM"+"\n"+
		"MLdnbv22wrAs1t2mxRk/iqWa5Hov1LtPMehpSvltV9lBtBnwD4JPQGnIuTZFgFUD"+"\n"+
		"LHUHul0pEQ5hOjNVOZ3rVbPsLaZ8gAd2DhT6ctKysuTOIyKUAqKVoLAmRXH3ipyA"+"\n"+
		"JcXjWXrSl29ntt69WVXbDQoZAoGANoa8hBYDdtd8JcOVuDp7EmSzfpTCTxXlpnuI"+"\n"+
		"SFUDGzcp5ty8AyhT3FMIptYYo6q7dwwGLtGW4UDL5dUUAvciwT3HQxnWKeMyqxta"+"\n"+
		"DZClJalsmsb02dTbsjFutc7/q6a+GjSG5Niy6MkIuXQ2qLxfCGQTemF7+lGQp4HU"+"\n"+
		"UIG//PECgYEArCBreT/TDb4M9tY9K0UoOGr9sfnmkUyenGHkUZ+Z3jaNXTW9TSvD"+"\n"+
		"fZoRA85ol5M1QdmOHKXuOhZXaresFw9E3M92hFKrp5XBo1cTthWbkPsUrj6WmWf6"+"\n"+
		"b2h6lEAZ0zjcWjGzwW+W1N/HVs833/qydQ4heQEC60K/gUreSKekA0I="+"\n"+
		"-----END RSA PRIVATE KEY-----";
	
	
	
	
	
	
	
	@Test(	description="Verify that the base RHEL product certs on the CDN for each release correctly reflect the release version.  For example, this affects users that want use subcription-manager release --set=6.3 to keep yum updates fixed to an older release.",
			groups={"VerifyBaseRHELProductCertVersionFromEachCDNReleaseVersion_Test","AcceptanceTests","Tier1Tests"},
			dataProvider="VerifyBaseRHELProductCertVersionFromEachCDNReleaseVersion_TestData",
			enabled=true)
	//@ImplementsNitrateTest(caseId=)
	public void VerifyBaseRHELProductCertVersionFromEachCDNReleaseVersion_Test(Object blockedByBug, String release, String rhelRepoUrl, File rhelEntitlementCertFile, File caCertFile) {
		
		File certFile = rhelEntitlementCertFile;
		File keyFile = clienttasks.getEntitlementCertKeyFileCorrespondingToEntitlementCertFile(rhelEntitlementCertFile);
		
		// determine the exact path to the productid on the CDN
		// Repo URL:  https://cdn.redhat.com/content/dist/rhel/server/6/$releasever/$basearch/os
		// ProductId: https://cdn.redhat.com/content/dist/rhel/server/6/$releasever/$basearch/os/repodata/productid
		String basearch = clienttasks.arch;
		if (basearch.equals("i686")||basearch.equals("i586")||basearch.equals("i486")) basearch="i386";	// releng content for these systems is published under arch i386
		String rhelRepoUrlToProductId = rhelRepoUrl.replace("$releasever",release).replace("$basearch",basearch)+"/repodata/productid";
		
		// use the entitlement certificates to get the productid
		File localProductIdFile = new File("/tmp/productid");
		RemoteFileTasks.runCommandAndAssert(client,"wget -nv -O "+localProductIdFile+" --ca-certificate="+caCertFile+" --certificate-type=PEM --certificate="+certFile+" --private-key="+keyFile+" --private-key-type=pem "+rhelRepoUrlToProductId,Integer.valueOf(0),null,"-> \""+localProductIdFile+"\"");
		//	[root@dell-pe650-02 ~]# wget -nv -O /tmp/productid --ca-certificate=/etc/rhsm/ca/redhat-uep.pem --certificate-type=PEM --certificate=/etc/pki/entitlement/19553491962157768.pem --private-key=/etc/pki/entitlement/19553491962157768-key.pem --private-key-type=pem https://cdn.redhat.com/content/dist/rhel/server/6/6.1/x86_64/os/repodata/productid
		//	2015-04-29 11:58:44 URL:https://cdn.redhat.com/content/dist/rhel/server/6/6.1/x86_64/os/repodata/productid [2163/2163] -> "/tmp/productid" [1]
		//	[root@dell-pe650-02 ~]# rct cat-cert /tmp/productid | grep -A8 "Product:"
		//	Product:
		//		ID: 69
		//		Name: Red Hat Enterprise Linux 6 Server
		//		Version: 6.1
		//		Arch: x86_64
		//		Tags: rhel-6,rhel-6-server
		//		Brand Type: 
		//		Brand Name: 

		// create a ProductCert corresponding to the productid file
		ProductCert productIdCert = clienttasks.getProductCertFromProductCertFile(localProductIdFile);
		
		// assert the expected productIdCert release version
		String expectedRelease = release;
		if (!isFloat(expectedRelease)) {	// happens when release is 6Server
			// in this case of 6Server, the newset release version should be expected (TODO, not sure this is entirely true)
			expectedRelease = getNewestReleaseFromReleases(clienttasks.getCurrentlyAvailableReleases(null, null, null));
		}
		Assert.assertEquals(productIdCert.productNamespace.version, expectedRelease,"Version of the productid on the CDN at '"+rhelRepoUrlToProductId+"' that will be installed by the yum product-id plugin after setting the subscription-manager release to '"+release+"'.");
	}
	@DataProvider(name="VerifyBaseRHELProductCertVersionFromEachCDNReleaseVersion_TestData")
	public Object[][] getVerifyBaseRHELProductCertVersionFromEachCDNReleaseVersion_TestDataAs2dArray() {
		return TestNGUtils.convertListOfListsTo2dArray(getVerifyBaseRHELProductCertVersionFromEachCDNReleaseVersion_TestDataAsListOfLists());
	}
	protected List<List<Object>> getVerifyBaseRHELProductCertVersionFromEachCDNReleaseVersion_TestDataAsListOfLists() {
		List<List<Object>> ll = new ArrayList<List<Object>>();
		if (!isSetupBeforeSuiteComplete) return ll;
		if (clienttasks==null) return ll;
		
		// unregister
		clienttasks.unregister(null, null, null);
		
		// get the currently installed RHEL product cert
		ProductCert rhelProductCert=clienttasks.getCurrentRhelProductCert();
		if (rhelProductCert==null) throw new SkipException("Failed to find an installed RHEL product cert.");	// rhel product cert cannot be subscribed if a rhel product cert is not installed
		
		// register with autosubscribe
		clienttasks.register(sm_clientUsername, sm_clientPassword, sm_clientOrg, null, null, null, null, true, null, null, (String)null, null, null, null, null, null, null, null, null);
		
		// find the autosubscribed entitlement that provides access to RHEL content 
		List<EntitlementCert> rhelEntitlementCerts = clienttasks.getEntitlementCertsProvidingProductCert(rhelProductCert);
		if(rhelEntitlementCerts.isEmpty()) {
			log.warning("Could not find an entitlement to a RHEL subscription.");
			return ll;
		}
		EntitlementCert rhelEntitlementCert = clienttasks.getEntitlementCertsProvidingProductCert(rhelProductCert).get(0);
		
		// get the cacert file
		File caCertFile = new File(clienttasks.getConfParameter("repo_ca_cert"));
		
		// get the repo url to the currently enabled base RHEL repo (assume it ends in /6/$releasever/$basearch/os
		// get the list of currently enabled repos
		//	[root@jsefler-os6 ~]# subscription-manager repos --list-enabled 
		//	+----------------------------------------------------------+
		//	    Available Repositories in /etc/yum.repos.d/redhat.repo
		//	+----------------------------------------------------------+
		//	Repo ID:   rhel-6-server-rpms
		//	Repo Name: Red Hat Enterprise Linux 6 Server (RPMs)
		//	Repo URL:  https://cdn.redhat.com/content/dist/rhel/server/6/$releasever/$basearch/os
		//	Enabled:   1
		String rhelRepoUrl = null;
		for (Repo enabledRepo :  Repo.parse(clienttasks.repos(null, true, false, (String)null,(String)null,null, null, null).getStdout())) {
			if (enabledRepo.enabled) {
				if (enabledRepo.repoUrl.endsWith(clienttasks.redhatReleaseX+"/$releasever/$basearch/os")) {
					if (rhelRepoUrl!=null && !rhelRepoUrl.equals(enabledRepo.repoUrl)) {
						// NOTE: I just learned that this will happen when the subscription also provides:  Red Hat Enterprise Linux Server - Extended Update Support
						// In this case an additional product cert could get installed...
						//	[root@dell-pe650-02 ~]# wget -nv -O /tmp/productid --ca-certificate=/etc/rhsm/ca/redhat-uep.pem --certificate-type=PEM --certificate=/etc/pki/entitlement/19553491962157768.pem --private-key=/etc/pki/entitlement/19553491962157768-key.pem --private-key-type=pem https://cdn.redhat.com/content/eus/rhel/server/6/6.1/x86_64/os/repodata/productid
						//	2015-04-29 12:01:28 URL:https://cdn.redhat.com/content/eus/rhel/server/6/6.1/x86_64/os/repodata/productid [2208/2208] -> "/tmp/productid" [1]
						//	[root@dell-pe650-02 ~]# rct cat-cert /tmp/productid | grep -A8 "Product:"Product:
						//		ID: 70
						//		Name: Red Hat Enterprise Linux Server - Extended Update Support
						//		Version: 6.1
						//		Arch: x86_64
						//		Tags: rhel-6-eus-server,rhel-6-server
						//		Brand Type: 
						//		Brand Name: 
						
						// repoName='Red Hat Enterprise Linux 6 Server - Extended Update Support (RPMs)' repoId='rhel-6-server-eus-rpms' repoUrl='https://cdn.redhat.com/content/eus/rhel/server/6/$releasever/$basearch/os' enabled='true'
						if (enabledRepo.repoUrl.contains("/eus/")) continue;	// skip Extended Update Support repos
						Assert.fail("Excluding EUS, encountered multiple enabled repos enabled that appear to serve the base RHEL content.  Did not expect this:\n "+rhelRepoUrl+"\n "+enabledRepo.repoUrl);
					}
					rhelRepoUrl = enabledRepo.repoUrl;
				}
			}
		}
		
		// add each available release as a row to the dataProvider
		for (String release : clienttasks.getCurrentlyAvailableReleases(null, null, null)) {
			List<String> bugIds = new ArrayList<String>();
			
			if (release.equals("6.2")) bugIds.add("1214856"); 	// Bug 1214856 - cdn.redhat.com has the wrong productId version for rhel 6.2 and 6.4
			if (release.equals("6.4")) bugIds.add("1214856"); 	// Bug 1214856 - cdn.redhat.com has the wrong productId version for rhel 6.2 and 6.4
			
			BlockedByBzBug blockedByBzBug = new BlockedByBzBug(bugIds.toArray(new String[]{}));
			
			// Object blockedByBug, String release, String rhelRepoUrl, File rhelEntitlementCertFile, File caCertFile
			ll.add(Arrays.asList(new Object[]{blockedByBzBug, release, rhelRepoUrl, rhelEntitlementCert.file, caCertFile}));
		}
		
		return ll;
	}
	
	
	
	@Test(	description="Verify that the base RHEL product cert will upgrade to match the releaseVer set when a package is installed/upgraded/downgraded",
			groups={"VerifyBaseRHELProductCertVersionUpdates_Test","AcceptanceTests","Tier1Tests"},
			dataProvider="getVerifyBaseRHELProductCertVersionUpdates_TestData",
			enabled=true)
	//@ImplementsNitrateTest(caseId=)
	public void VerifyBaseRHELProductCertVersionUpdates_Test(Object blockedByBug, String testPackage, String oldProductCertVersion, String oldRelease, String newerRelease) {
		clienttasks.unregister(null, null, null);
		restoreOriginalRhsmProductCertDirAndProductIdJsonFile();
		
		// Step 0: remove the test package
		if (clienttasks.isPackageInstalled(testPackage)) clienttasks.yumRemovePackage(testPackage, "--disablerepo=beaker-*");	// need to disable the beaker repos (actually all repos that contain a productid in the metadata would be best) to prevent the yum product-id plugin from considering it for an update to the installed RHEL product cert 
		
		// Step 1: determine the currently installed RHEL product cert
		ProductCert originalRhelProductCert=clienttasks.getCurrentRhelProductCert();
		Assert.assertNotNull(originalRhelProductCert,"Expected a base RHEL product cert to be installed.");
		
		// Step 2: configure a temporary productCertDir and backup the productid json database file
		log.info("Configuring a temporary product cert directory...");
		if (rhsmProductCertDir==null) {rhsmProductCertDir = clienttasks.getConfFileParameter(clienttasks.rhsmConfFile, "rhsm", "productCertDir");Assert.assertNotNull(rhsmProductCertDir);} // remember the original so it can be restored later
		RemoteFileTasks.runCommandAndAssert(client,"mkdir -p "+tmpProductCertDir,Integer.valueOf(0));
		RemoteFileTasks.runCommandAndAssert(client,"rm -f "+tmpProductCertDir+"/*.pem",Integer.valueOf(0));
		clienttasks.updateConfFileParameter(clienttasks.rhsmConfFile, "productCertDir", tmpProductCertDir);
		if (productIdJsonFile==null) {productIdJsonFile = client.runCommandAndWait("cat "+clienttasks.productIdJsonFile).getStdout().replaceAll("\\s*\n\\s*",""); Assert.assertTrue(productIdJsonFile!=null && !productIdJsonFile.isEmpty());} // remember the original so it can be restored later
		
		// Step 3: install an old RHEL product cert
		ProductCert oldProductCert = null;	
		//List<ProductCert> migrationProductCerts = clienttasks.getProductCerts("/usr/share/rhsm/product/RHEL-"+clienttasks.redhatReleaseX);
		//for (ProductCert productCert : migrationProductCerts) {	//find the old rhel product cert from the migration product certs supplied by the subscription-manager-migration-data
		for (ProductCert productCert : rhelProductCertsFromRhnDefinition) {	//find the old rhel product cert from a clone of the rhn definition repo data
			if (productCert.productId.equals(originalRhelProductCert.productId)) {
				if (productCert.productNamespace.arch.equals(originalRhelProductCert.productNamespace.arch)) {
					if (productCert.productNamespace.version.equals(oldProductCertVersion)) {
						oldProductCert = productCert;
						break;
					}
				}
			}
		}
		Assert.assertNotNull(oldProductCert,"Initiating test with this old RHEL product cert: "+oldProductCert);
		RemoteFileTasks.runCommandAndAssert(client,"cp "+oldProductCert.file+" "+tmpProductCertDir+"/"+oldProductCert.productId+".pem",Integer.valueOf(0));
		Assert.assertNotNull(clienttasks.getInstalledProductCorrespondingToProductCert(oldProductCert),"The old product cert is installed and recognized by subscription-manager.");

		// Step 4: register with autosubscribe for RHEL content
		clienttasks.register(sm_clientUsername, sm_clientPassword, sm_clientOrg, null, null, null, null, true, null, null, (String)null, null, null, null, null, null, null, null, null);
		if (!clienttasks.isRhelProductCertSubscribed()) throw new SkipException("Failed to autosubscribe to an available RHEL subscription.");
		
		// determine the newest available release
		List<String> availableReleases = clienttasks.getCurrentlyAvailableReleases(null, null, null);	// Example: [6.1, 6.2, 6.3, 6.4, 6.5, 6.6, 6Server] 6.6 is the newest
		String newestRelease = getNewestReleaseFromReleases(availableReleases);
		
		// Step 5: set the GA release corresponding to the old product cert version
		clienttasks.release(null, null, oldRelease, null, null, null, null);
		
		// Note: yum @productid.py:290 - Checking for product id certs to install or update.  THIS ONLY TRIGGERS WHEN THE RPM LIST IS CHANGED BY A REMOVAL, INSTALL, OR UPDATE.

		// Step 6: install the test package and assert the product id has been upgraded.
		clienttasks.yumClean("metadata");	// to avoid... Not using downloaded repomd.xml because it is older than what we have:
		clienttasks.yumInstallPackage(testPackage,"--disablerepo=beaker-*");	// need to disable the beaker repos (actually all repos that contain a productid in the metadata would be best) to prevent the yum product-id plugin from considering it for an update to the installed RHEL product cert 
		// tail -f /var/log/rhsm/rhsm.log
		//	2014-05-16 12:04:27,444 [DEBUG] yum @productid.py:290 - Checking for product id certs to install or update.
		//	2014-05-16 12:04:27,449 [DEBUG] yum @productid.py:304 - product cert: 69 repo: rhel-6-server-cf-tools-1-rpms
		//	2014-05-16 12:04:27,450 [DEBUG] yum @productid.py:363 - Latest version of product cert for Red Hat Enterprise Linux Server 6.3 is already install, not updating
		//	2014-05-16 12:04:27,451 [DEBUG] yum @productid.py:304 - product cert: 69 repo: rhel-6-server-rpms
		//	2014-05-16 12:04:27,452 [DEBUG] yum @productid.py:363 - Latest version of product cert for Red Hat Enterprise Linux Server 6.3 is already install, not updating
		//	2014-05-16 12:04:27,453 [DEBUG] yum @productid.py:407 - about to run post_product_id_install
		//	2014-05-16 12:04:27,453 [DEBUG] yum @productid.py:418 - about to run post_product_id_update
		ProductCert rhelProductCert=clienttasks.getCurrentRhelProductCert();
		Assert.assertNotNull(rhelProductCert,"Expected a base RHEL product cert to be installed.");
		Assert.assertEquals(rhelProductCert.productNamespace.version, oldRelease, "After installing package '"+testPackage+"' from release '"+oldRelease+"', the installed product cert version is updated from '"+oldProductCertVersion+"'.");
		
		// remove the test package
		clienttasks.yumRemovePackage(testPackage, "--disablerepo=beaker-*");	// need to disable the beaker repos (actually all repos that contain a productid in the metadata would be best) to prevent the yum product-id plugin from considering it for an update to the installed RHEL product cert 
		
		
		
		// Step 7: set a newer release than the installed product id
		clienttasks.release(null, null, newerRelease, null, null, null, null);
		
		// Step 8: install the test package and assert the product id has been upgraded.
		clienttasks.yumInstallPackage(testPackage,"--disablerepo=beaker-*");	// need to disable the beaker repos (actually all repos that contain a productid in the metadata would be best) to prevent the yum product-id plugin from considering it for an update to the installed RHEL product cert 
		// tail -f /var/log/rhsm/rhsm.log
		//	2014-05-16 12:07:02,447 [DEBUG] yum @productid.py:290 - Checking for product id certs to install or update.
		//	2014-05-16 12:07:02,448 [DEBUG] yum @productid.py:304 - product cert: 69 repo: rhel-6-server-cf-tools-1-rpms
		//	2014-05-16 12:07:02,448 [DEBUG] yum @productid.py:363 - Latest version of product cert for Red Hat Enterprise Linux Server 6.3 is already install, not updating
		//	2014-05-16 12:07:02,448 [DEBUG] yum @productid.py:304 - product cert: 69 repo: rhel-6-server-rpms
		//	2014-05-16 12:07:02,448 [DEBUG] yum @productid.py:359 - Updating installed product cert for Red Hat Enterprise Linux Server 6.3 to Red Hat Enterprise Linux Server 6.4
		//	2014-05-16 12:07:02,449 [DEBUG] yum @productid.py:407 - about to run post_product_id_install
		//	2014-05-16 12:07:02,449 [INFO] yum @productid.py:430 - Installed product cert 69: Red Hat Enterprise Linux Server /tmp/sm-tmpProductCertDir/69.pem
		//	2014-05-16 12:07:02,450 [DEBUG] yum @productid.py:418 - about to run post_product_id_update
		rhelProductCert=clienttasks.getCurrentRhelProductCert();
		Assert.assertNotNull(rhelProductCert,"Expected a base RHEL product cert to be installed.");
		Assert.assertEquals(rhelProductCert.productNamespace.version, newerRelease, "After installing package '"+testPackage+"' from release '"+newerRelease+"', the installed product cert version is updated from '"+oldProductCertVersion+"'.");
		
		
		
		// Step 9: unset the release thereby giving access to the latest content
		clienttasks.release(null, null, null, true, null, null, null);
		
		// Step 10: yum update the test package to the latest version and assert the product id has been upgraded to the original product cert.
		clienttasks.yumUpdatePackageFromRepo(testPackage, null, "--disablerepo=beaker-*");	// need to disable the beaker repos (actually all repos that contain a productid in the metadata would be best) to prevent the yum product-id plugin from considering it for an update to the installed RHEL product cert 
		// tail -f /var/log/rhsm/rhsm.log
		//	2014-05-16 12:09:23,974 [DEBUG] yum @productid.py:290 - Checking for product id certs to install or update.
		//	2014-05-16 12:09:23,975 [DEBUG] yum @productid.py:304 - product cert: 69 repo: rhel-6-server-cf-tools-1-rpms
		//	2014-05-16 12:09:23,975 [DEBUG] yum @productid.py:363 - Latest version of product cert for Red Hat Enterprise Linux Server 6.3 is already install, not updating
		//	2014-05-16 12:09:23,975 [DEBUG] yum @productid.py:304 - product cert: 69 repo: rhel-6-server-rpms
		//	2014-05-16 12:09:23,975 [DEBUG] yum @productid.py:359 - Updating installed product cert for Red Hat Enterprise Linux Server 6.4 to Red Hat Enterprise Linux Server 6.5
		//	2014-05-16 12:09:23,976 [DEBUG] yum @productid.py:407 - about to run post_product_id_install
		//	2014-05-16 12:09:23,976 [INFO] yum @productid.py:430 - Installed product cert 69: Red Hat Enterprise Linux Server /tmp/sm-tmpProductCertDir/69.pem
		//	2014-05-16 12:09:23,976 [DEBUG] yum @productid.py:418 - about to run post_product_id_update
		rhelProductCert=clienttasks.getCurrentRhelProductCert();
		Assert.assertNotNull(rhelProductCert,"Expected a base RHEL product cert to be installed.");
		//if (!originalRhelProductCert.productNamespace.version.toLowerCase().contains("beta")) {	// skip assertion when originalRhelProductCert is a beta cert since a beta productId will not be available for the latest release on the CDN 
		//	Assert.assertEquals(rhelProductCert.productNamespace.version, originalRhelProductCert.productNamespace.version, "After updating package '"+testPackage+"' to the latest release, the installed product cert version is updated from '"+newerRelease+"' to the original version '"+originalRhelProductCert.productNamespace.version+"'.");
		//}
		Assert.assertEquals(rhelProductCert.productNamespace.version, newestRelease, "After updating package '"+testPackage+"' to the latest release, the installed product cert version is updated from '"+newerRelease+"' to the latest released version '"+newestRelease+"'.");
		
		
		// Step 11: set the rhel release backward to perform downgrade testing
		clienttasks.release(null, null, newerRelease, null, null, null, null);
		
		// Step 12: downgrade the test package and assert the original product id remains installed.
		clienttasks.yumClean("metadata");	// to avoid... Not using downloaded repomd.xml because it is older than what we have:
		clienttasks.yumDowngradePackageFromRepo(testPackage, null, "--disablerepo=beaker-*");	// need to disable the beaker repos (actually all repos that contain a productid in the metadata would be best) to prevent the yum product-id plugin from considering it for an update to the installed RHEL product cert 
		// tail -f /var/log/rhsm/rhsm.log
		//	2014-05-16 12:11:48,233 [DEBUG] yum @productid.py:290 - Checking for product id certs to install or update.
		//	2014-05-16 12:11:48,233 [DEBUG] yum @productid.py:304 - product cert: 69 repo: rhel-6-server-cf-tools-1-rpms
		//	2014-05-16 12:11:48,233 [DEBUG] yum @productid.py:363 - Latest version of product cert for Red Hat Enterprise Linux Server 6.3 is already install, not updating
		//	2014-05-16 12:11:48,234 [DEBUG] yum @productid.py:304 - product cert: 69 repo: rhel-6-server-rpms
		//	2014-05-16 12:11:48,234 [DEBUG] yum @productid.py:363 - Latest version of product cert for Red Hat Enterprise Linux Server 6.4 is already install, not updating
		//	2014-05-16 12:11:48,234 [DEBUG] yum @productid.py:407 - about to run post_product_id_install
		//	2014-05-16 12:11:48,234 [DEBUG] yum @productid.py:418 - about to run post_product_id_update
		ProductCert finalRhelProductCert=clienttasks.getCurrentRhelProductCert();
		Assert.assertNotNull(finalRhelProductCert,"Expected a base RHEL product cert to be installed.");
		//if (!originalRhelProductCert.productNamespace.version.toLowerCase().contains("beta")) {	// skip assertion when originalRhelProductCert is a beta cert since a beta productId will not be available for the latest release on the CDN 
		//	Assert.assertEquals(finalRhelProductCert.productNamespace.version, originalRhelProductCert.productNamespace.version, "After downgrading package '"+testPackage+"' from the latest release, the installed product cert version remains the same as the original version '"+originalRhelProductCert.productNamespace.version+"'.");
		//}
		Assert.assertEquals(finalRhelProductCert.productNamespace.version, newestRelease, "After downgrading package '"+testPackage+"' from the latest release '"+newestRelease+"' to '"+newerRelease+"', the installed product cert version remains unchanged at release version '"+newestRelease+"'.");
	}
	@DataProvider(name="getVerifyBaseRHELProductCertVersionUpdates_TestData")
	public Object[][] getVerifyBaseRHELProductCertVersionUpdates_TestDataAs2dArray() {
		return TestNGUtils.convertListOfListsTo2dArray(getVerifyBaseRHELProductCertVersionUpdates_TestDataAsListOfLists());
	}
	protected List<List<Object>> getVerifyBaseRHELProductCertVersionUpdates_TestDataAsListOfLists() {
		List<List<Object>> ll = new ArrayList<List<Object>>();
		if (!isSetupBeforeSuiteComplete) return ll;
		if (clienttasks==null) return ll;
		
		// Step 0: remove the test package (choose a RHEL package that changes often)
		// https://errata.devel.redhat.com/package/show/zsh
		//	RHEL-5.8.0 		zsh-4.2.6-6.el5
		//	RHEL-5.10.0 	zsh-4.2.6-9.el5
		//	RHEL-6.3.0 		zsh-4.3.10-5.el6
		//	RHEL-6.5.0 		zsh-4.3.10-7.el6
		//	RHEL-7.0.0		sudo-1.8.6p7-11.el7
		//	RHEL-7.1.0		sudo-1.8.6p7-12.el7

		// Object blockedByBug, String testPackage, String oldProductCertVersion, String oldRelease, String newerRelease
		if (clienttasks.redhatReleaseX.equals("5")) {
			ll.add(Arrays.asList(new Object[]{new BlockedByBzBug(new String[]{"1086301","1102107","1119809"}),	"zsh",	"5.8",		"5.8",	"5.9"}));
			ll.add(Arrays.asList(new Object[]{new BlockedByBzBug(new String[]{"1086301","1102107","1119809"}),	"zsh",	"5.8 Beta",	"5.8",	"5.9"}));
		}
		else if (clienttasks.redhatReleaseX.equals("6")) {
			ll.add(Arrays.asList(new Object[]{new BlockedByBzBug(new String[]{"1035115","1000281","1120573","1214856"}),	"zsh",	"6.3",		"6.3",	"6.4"}));
			ll.add(Arrays.asList(new Object[]{new BlockedByBzBug(new String[]{"1035115","1000281","1120573","1214856"}),	"zsh",	"6.3 Beta",	"6.3",	"6.4"}));
			ll.add(Arrays.asList(new Object[]{new BlockedByBzBug(new String[]{"1035115","1000281","1120573"}),	"zsh",	"6.1",		"6.1",	"6.5"}));
			//ll.add(Arrays.asList(new Object[]{new BlockedByBzBug(new String[]{"1035115","1000281","1120573"}),	"zsh",	"6.1-Beta",	"6.1",	"6.5"}));	// will fail on ppc64 and s390x because the 6.1-Beta product cert tags do not provide any content (on ppc64 rhel-6.1-beta productId=74 Tags: rhel-6,rhel-6-premium-architectures) (on s390x rhel-6.1-beta productId=72 Tags: rhel-6,rhel-6-mainframe)  Not opening any bug since it was a Beta.
			ll.add(Arrays.asList(new Object[]{new BlockedByBzBug(new String[]{"1035115","1000281","1120573"}),	"zsh",	"6.3 Beta",	"6.3",	"6.5"}));
		}
		else if (clienttasks.redhatReleaseX.equals("7") && !clienttasks.redhatReleaseXY.equals("7.0") && !clienttasks.redhatReleaseXY.equals("7.1")) {	// Note: this test depends on an available Release level of 7.0 and 7.1 in the release listing file which will not be available until the rhel 7.2 test cycle; skipping until we test rhel 7.2
			ll.add(Arrays.asList(new Object[]{null,	"crash"/* wirehark FIXME choose a simple package that has been updated between each release 7.0 to 7.1 to 7.2*/ ,	"7.0",		"7.0",	"7.1"}));
			//ll.add(Arrays.asList(new Object[]{null,	"wirehark",	"7.0 Beta",	"7.0",	"7.1"}));	// There is no 7.0 Beta product cert id 69 that can be updated.  The 7.0 Beta product cert is product id 226 Everything
		}
		else if (Integer.valueOf(clienttasks.redhatReleaseX)>7) {
			ll.add(Arrays.asList(new Object[]{null,	"FIXME: Unhandled Release",	"1.0 Beta",	"1.0",	"1.1"}));
		}
		return ll;
	}
	@AfterGroups(groups="setup",
//			value = {"VerifyBaseRHELProductCertVersionUpdates_Test"}
			value = {"VerifyBaseRHELProductCertVersionUpdates_Test","VerifyYumUpdateWithDisabledRepoWillNotDeleteJBossProductId_Test"}
	)
	public void restoreOriginalRhsmProductCertDirAndProductIdJsonFile() {
		if (clienttasks==null) return;
		
		if (rhsmProductCertDir!=null) {
			log.info("Restoring the originally configured product cert directory...");
			clienttasks.updateConfFileParameter(clienttasks.rhsmConfFile, "productCertDir", rhsmProductCertDir);
			rhsmProductCertDir=null;
		}
		
		if (productIdJsonFile != null) {
			log.info("Restoring the original productid json database file...");
			RemoteFileTasks.runCommandAndAssert(client,"echo '"+productIdJsonFile+"' > "+clienttasks.productIdJsonFile, Integer.valueOf(0));
			productIdJsonFile=null;
//			clienttasks.yumClean("all");
		}
		clienttasks.yumClean("all");
	}
	@BeforeGroups(groups="setup", value = {"VerifyBaseRHELProductCertVersionUpdates_Test"})
	protected void findAllRhelProductCertsFromRhnDefinitions() {
		// assemble a list of rhnDefinitionsProductCertsDirs that we care about under [rcm/rcm-metadata.git] / product_ids /
		// Note: we care about all of the productCertsDirs
		SSHCommandResult result = client.runCommandAndWait("find "+clienttasks.rhnDefinitionsDir+"/product_ids -maxdepth 1 -type d");
		List<String> rhnDefinitionsProductCertsDirs = new ArrayList<String>();
		for (String productIdsDir : result.getStdout().split("\\n")) {
			if (!productIdsDir.equals(clienttasks.rhnDefinitionsDir+"/product_ids")) {
				// put logic here to exclude specific directories
				
				// include only /rhel-X product cert dirs; for example: /rhel-6.5-beta /rhel-6.5 /rhel-6.6-beta /rhel-6.6-eus /rhel-6.6-htb /rhel-6.6
				// http://git.app.eng.bos.redhat.com/git/rcm/rcm-metadata.git/tree/product_ids
				if (!productIdsDir.contains("/rhel-"+clienttasks.redhatReleaseX+".")) continue;
				
				rhnDefinitionsProductCertsDirs.add(productIdsDir);
			}
		}
		Assert.assertTrue(!rhnDefinitionsProductCertsDirs.isEmpty(),"The "+clienttasks.rhnDefinitionsDir+"/product_ids is not empty.");
		
		// process all the migration product cert files into ProductCerts and assert they match those from the RHN Definitions
		
		// get all of the rhnDefnition product certs
		rhelProductCertsFromRhnDefinition.clear();
		for (String rhnDefinitionsProductCertsDir : /*sm_*/rhnDefinitionsProductCertsDirs) {
			String tmpRhnDefinitionsProductCertsDir = /*clienttasks.rhnDefinitionsDir+*/rhnDefinitionsProductCertsDir;
			Assert.assertTrue(RemoteFileTasks.testExists(client, tmpRhnDefinitionsProductCertsDir),"The rhn definitions product certs dir '"+rhnDefinitionsProductCertsDir+"' has been locally cloned to '"+tmpRhnDefinitionsProductCertsDir+"'.");
			rhelProductCertsFromRhnDefinition.addAll(clienttasks.getProductCerts(tmpRhnDefinitionsProductCertsDir));
		}
	}
	protected String rhsmProductCertDir = null;	// original rhsm.productCertDir
	protected final String tmpProductCertDir = "/tmp/sm-tmpProductCertDir";
	protected List<ProductCert> rhelProductCertsFromRhnDefinition = new ArrayList<ProductCert>();
	protected String productIdJsonFile = null;	// original /var/lib/rhsm/productid.js
	
	
	
	
	
	
	
	
	@Test(	description="When updating a RHEL package on a system with JBoss, verify that the JBoss productId is not deleted when calling yum update with --disablerepo=jb-eap-6-for-rhel-6-server-rpms",
			groups={"VerifyYumUpdateWithDisabledRepoWillNotDeleteJBossProductId_Test","AcceptanceTests","Tier1Tests","blockedByBug-1159163"},
			enabled=true)
	//@ImplementsNitrateTest(caseId=)
	public void VerifyYumUpdateWithDisabledRepoWillNotDeleteJBossProductId_Test() throws JSONException, Exception {
		// fixed in https://bugzilla.redhat.com/show_bug.cgi?id=1159163#c12  subscription-manager commit 68d210bb9145e7ea65aea979fde694436e3e0373 subscription-manager-1.14.6-1
		clienttasks.unregister(null, null, null);
		restoreOriginalRhsmProductCertDirAndProductIdJsonFile();
		
		// hard data for this test...
		String rhelPackage		= "zsh";
		String rhelRepo			= "rhel-6-server-rpms";	// Required Tags: rhel-6-server Arches: x86_64, x86
		rhelRepo				= String.format("rhel-%s-server-rpms", clienttasks.redhatReleaseX);
		String rhelProductId	= "69";	// Red Hat Enterprise Linux Server	// Tags: rhel-6,rhel-6-server Arch: x86_64
		String jbossPackage		= "jline-eap6";	// rpm -q jline-eap6.noarch --requires  => rpmlib
		String jbossRepo		= "jb-eap-6-for-rhel-6-server-rpms";	// Required Tags: rhel-6-server Arches: x86_64, x86
		jbossRepo				= String.format("jb-eap-6-for-rhel-%s-server-rpms", clienttasks.redhatReleaseX);
		String jbossProductId	= "183";	// JBoss Enterprise Application Platform
		if (clienttasks.arch.equals("ppc64")) {
			rhelRepo			= "rhel-6-for-power-rpms";	// Required Tags: rhel-6-ibm-power Arches: ppc64
			rhelRepo			= String.format("rhel-%s-for-power-rpms", clienttasks.redhatReleaseX);
			rhelProductId		= "74";	// Red Hat Enterprise Linux for Power, big endian	// Tags: rhel-6,rhel-6-ibm-power Arch: ppc64
			jbossRepo			= "jb-eap-6-for-rhel-6-for-power-rpms";	// Required Tags: rhel-6-ibm-power Arches: ppc64
			jbossRepo			= String.format("jb-eap-6-for-rhel-%s-for-power-rpms", clienttasks.redhatReleaseX);
			jbossProductId		= "183";	// JBoss Enterprise Application Platform
		}
		
		// remove the test packages in case they are already installed
		if (clienttasks.isPackageInstalled(rhelPackage)) clienttasks.yumRemovePackage(rhelPackage, "--disablerepo=beaker-*");	// need to disable the beaker repos (actually all repos that contain a productid in the metadata would be best) to prevent the yum product-id plugin from considering it for an update to the installed RHEL product cert 
		if (clienttasks.isPackageInstalled(jbossPackage)) clienttasks.yumRemovePackage(jbossPackage, "--disablerepo=beaker-*");	// need to disable the beaker repos (actually all repos that contain a productid in the metadata would be best) to prevent the yum product-id plugin from considering it for an update to the installed RHEL product cert 
		
		// determine the currently installed product certs
		List <ProductCert> currentProductCerts = clienttasks.getCurrentProductCerts();
		ProductCert rhelProductCert = ProductCert.findFirstInstanceWithMatchingFieldFromList("productId", rhelProductId, currentProductCerts);
		if (rhelProductCert==null) throw new SkipException("JBoss Enterprise Application Platform is not offered on this variant '"+clienttasks.variant+"' of RHEL.");
		ProductCert jbossProductCert = ProductCert.findFirstInstanceWithMatchingFieldFromList("productId", jbossProductId, currentProductCerts);
		Assert.assertNull(jbossProductCert,"Do not expect the JBoss product to be installed at the beginning of this test.");
		
		// configure a temporary productCertDir and backup the productid json database file
		log.info("Configuring a temporary product cert directory...");
		if (rhsmProductCertDir==null) {rhsmProductCertDir = clienttasks.getConfFileParameter(clienttasks.rhsmConfFile, "rhsm", "productCertDir");Assert.assertNotNull(rhsmProductCertDir);} // remember the original so it can be restored later
		RemoteFileTasks.runCommandAndAssert(client,"mkdir -p "+tmpProductCertDir, Integer.valueOf(0));
		RemoteFileTasks.runCommandAndAssert(client,"rm -f "+tmpProductCertDir+"/*.pem", Integer.valueOf(0));
		RemoteFileTasks.runCommandAndWait(client,"cp "+rhsmProductCertDir+"/*.pem "+tmpProductCertDir, TestRecords.action()); // due to /etc/pki/product-default/ certs, this assert will fail when /etc/pki/product/ is empty  RemoteFileTasks.runCommandAndAssert(client,"cp "+rhsmProductCertDir+"/*.pem "+tmpProductCertDir, Integer.valueOf(0));
		clienttasks.updateConfFileParameter(clienttasks.rhsmConfFile, "productCertDir", tmpProductCertDir);
		if (productIdJsonFile==null) {productIdJsonFile = client.runCommandAndWait("cat "+clienttasks.productIdJsonFile).getStdout().replaceAll("\\s*\n\\s*",""); Assert.assertTrue(productIdJsonFile!=null && !productIdJsonFile.isEmpty());} // remember the original so it can be restored later
		
		// register with autosubscribe for rhel content
		clienttasks.register(sm_clientUsername, sm_clientPassword, sm_clientOrg, null, null, null, null, true, null, null, (String)null, null, null, null, null, null, null, null, null);
		List <InstalledProduct> currentlyInstalledProducts = clienttasks.getCurrentlyInstalledProducts();
		InstalledProduct rhelInstalledProduct = InstalledProduct.findFirstInstanceWithMatchingFieldFromList("productId", rhelProductId, currentlyInstalledProducts);
		Assert.assertNotNull(rhelInstalledProduct, "Expecting the installed RHEL Server product '"+rhelProductId+"' to be installed.");
		if (!rhelInstalledProduct.status.equals("Subscribed") && CandlepinType.standalone.equals(sm_serverType)) throw new SkipException("This test is designed for execution against a non-standalone candlepin server where available RHEL subscriptions are expected.");
		Assert.assertEquals(rhelInstalledProduct.status, "Subscribed","Expecting the RHEL Server product to have been auto-subscribed during registration.");
		
		// subscribe to JBoss Enterprise Application Platform - Example SKU MW0167254 Red Hat JBoss Enterprise Application Platform with Management, 16 Core Standard, L3 Support Partner)
		String jbossPoolId = null;
		List <SubscriptionPool> jbossSubscriptionPools = clienttasks.getCurrentlyAvailableSubscriptionPools(jbossProductId, sm_serverUrl);
		if (jbossSubscriptionPools.isEmpty() && !CandlepinType.standalone.equals(sm_serverType)) Assert.fail("Expected to find an available subscription pool for Red Hat JBoss Enterprise Application Platform.");
		if (jbossSubscriptionPools.isEmpty()) throw new SkipException("Cannot run this test when there is no available subscription for Red Hat JBoss Enterprise Application Platform.");
		// too many asserts... clienttasks.subscribeToSubscriptionPool(jbossSubscriptionPools.get(0));
		clienttasks.subscribe_(null, null, jbossSubscriptionPools.get(0).poolId, null, null, null, null, null, null, null, null, null);
		
		// enable the jb-eap-6-for-rhel-6-server-rpms repo
		// Note: This is necessary. If only enabled during yum install --enablerepo=jb-eap-6-for-rhel-6-server-rpms then the jboss product id will install, but it will also be deleted during the yum install of a rhel package.
		clienttasks.repos(null, null, null, jbossRepo, null, null, null, null);
		
		// yum install jline-eap6 from repo jb-eap-6-for-rhel-6-server-rpms
		//clienttasks.yumInstallPackageFromRepo(jbossPackage, jbossRepo, null);
		clienttasks.yumInstallPackage(jbossPackage);
		
		// assert that the jboss product id 183 has been newly installed
		currentlyInstalledProducts = clienttasks.getCurrentlyInstalledProducts();
		InstalledProduct jbossInstalledProduct = InstalledProduct.findFirstInstanceWithMatchingFieldFromList("productId", jbossProductId, currentlyInstalledProducts);
		Assert.assertNotNull(jbossInstalledProduct, "After installing jboss package '"+jbossPackage+"' from enabled jboss repo '"+jbossRepo+"', the jboss product id '"+jbossProductId+"' is installed.");
		
		// now for the real test...  install a rhel package with yum --disablerepo and then verify that the jboss product cert remains installed...
		//clienttasks.repos(null, null, null, rhelRepo, null, null, null, null); // is already enabled by default
		clienttasks.yumInstallPackage(rhelPackage, "--disablerepo="+jbossRepo);
		
		// verify that the jboss product cert remains installed - Bug 1159163 - RHSM product certificate gets deleted by product-id plugin on running 'yum update --disablerepo
		currentlyInstalledProducts = clienttasks.getCurrentlyInstalledProducts();
		jbossInstalledProduct = InstalledProduct.findFirstInstanceWithMatchingFieldFromList("productId", jbossProductId, currentlyInstalledProducts);
		Assert.assertNotNull(jbossInstalledProduct, "After installing rhel package '"+rhelPackage+"' while disabling the jboss repo '"+jbossRepo+"' in-line, the jboss product id '"+jbossProductId+"' remains installed.");
		
		
		// remove the only installed jboss package while disabling the rhel repo and verify the jboss product id has been removed, but the rhel product id remains
		// TEMPORARY WORKAROUND
		boolean invokeWorkaroundWhileBugIsOpen = true;
		String bugId="1222627"; // Bug 1222627 - deletion of JBOSS product certificate is neglected by product-id plugin on running 'yum remove JBOSS-PKG --disablerepo=RHEL-REPO'
		try {if (invokeWorkaroundWhileBugIsOpen&&BzChecker.getInstance().isBugOpen(bugId)) {log.fine("Invoking workaround for "+BzChecker.getInstance().getBugState(bugId).toString()+" Bugzilla "+bugId+".  (https://bugzilla.redhat.com/show_bug.cgi?id="+bugId+")");SubscriptionManagerCLITestScript.addInvokedWorkaround(bugId);} else {invokeWorkaroundWhileBugIsOpen=false;}} catch (XmlRpcException xre) {/* ignore exception */} catch (RuntimeException re) {/* ignore exception */}
		if (invokeWorkaroundWhileBugIsOpen) {
			log.warning("While bug '"+bugId+"' is open, we will exclude the --disablerepo="+rhelRepo+" option from the yum removal of the jboss package.");
			clienttasks.yumRemovePackage(jbossPackage); // THIS PASSES THE FOLLOWING Assert.assertNull(jbossInstalledProduct,...
		} else
		// END OF WORKAROUND
		clienttasks.yumRemovePackage(jbossPackage, "--disablerepo="+rhelRepo);	// THIS FAILS THE FOLLOWING Assert.assertNull(jbossInstalledProduct,...  due to Bug 1222627
		
		// verify that the jboss product cert is removed
		currentlyInstalledProducts = clienttasks.getCurrentlyInstalledProducts();
		jbossInstalledProduct = InstalledProduct.findFirstInstanceWithMatchingFieldFromList("productId", jbossProductId, currentlyInstalledProducts);
		Assert.assertNull(jbossInstalledProduct, "After removing jboss package '"+jbossPackage+"' while disabling rhel repo '"+rhelRepo+"', the jboss product id '"+jbossProductId+"' is removed (because it is the final remaining package installed from jboss repo '"+jbossRepo+"').");
		// verify that the rhel product cert remains installed
		rhelInstalledProduct = InstalledProduct.findFirstInstanceWithMatchingFieldFromList("productId", rhelProductId, currentlyInstalledProducts);
		Assert.assertNotNull(rhelInstalledProduct, "After removing jboss package '"+jbossPackage+"' while disabling rhel repo '"+rhelRepo+"', the rhel product id '"+rhelProductId+"' remains installed (because there are many other rhel packages from rhel repo '"+rhelRepo+"' still installed).");

		
		// remove the rhel package
		clienttasks.yumRemovePackage(rhelPackage);
		
		// verify that the jboss product cert remains uninstalled
		currentlyInstalledProducts = clienttasks.getCurrentlyInstalledProducts();
		jbossInstalledProduct = InstalledProduct.findFirstInstanceWithMatchingFieldFromList("productId", jbossProductId, currentlyInstalledProducts);
		Assert.assertNull(jbossInstalledProduct, "After removing rhel package '"+rhelPackage+"', the jboss product id '"+jbossProductId+"' from enabled repo '"+jbossRepo+"' remains uninstalled.");
		// verify that the rhel product cert remains installed
		rhelInstalledProduct = InstalledProduct.findFirstInstanceWithMatchingFieldFromList("productId", rhelProductId, currentlyInstalledProducts);
		Assert.assertNotNull(rhelInstalledProduct, "After removing rhel package '"+rhelPackage+"' the rhel product id '"+rhelProductId+"' remains installed (because there are many other rhel packages from rhel repo '"+rhelRepo+"' still installed).");
	}
	
	
	
	
	
	
	// Candidates for an automated Test:
	// TODO https://bugzilla.redhat.com/show_bug.cgi?id=659735
	// TODO Bug 805690 - gpgcheck for repo metadata is ignored https://github.com/RedHatQE/rhsm-qe/issues/117
	// TODO Bug 806958 - One empty certificate file in /etc/rhsm/ca causes registration failure https://github.com/RedHatQE/rhsm-qe/issues/118
	
	// Configuration methods ***********************************************************************
	
	
	
	// Protected methods ***********************************************************************
	
	/**
	 * @param releases - from getCurrentlyAvailableReleases() Example: [6.1, 6.2, 6.3, 6.4, 6.5, 6.6, 6Server] 6.6 is the newest 
	 * @return
	 */
	String getNewestReleaseFromReleases(List<String> releases) {
		String newestRelease = null;
		for (String release : releases) {
			if (isFloat(release)) {
				if (newestRelease==null) {
					newestRelease=release;
				} else if (Float.valueOf(release) > Float.valueOf(newestRelease)) {
					newestRelease=release;
				}
			}
		}
		return newestRelease;
	}
	
	
	// Data Providers ***********************************************************************
	@DataProvider(name="getProductCertFilesData")
	public Object[][] getProductCertFilesDataAs2dArray() {
		return TestNGUtils.convertListOfListsTo2dArray(getProductCertFilesDataAsListOfLists());
	}
	protected List<List<Object>> getProductCertFilesDataAsListOfLists() {
		List<List<Object>> ll = new ArrayList<List<Object>>(); if (!isSetupBeforeSuiteComplete) return ll;
		
		if (clienttasks!=null) {
			
			// get all of the installed product certs
			List<File> productCertFiles = clienttasks.getCurrentProductCertFiles();
			if (productCertFiles.size()==0) log.warning("No Product Cert files were found.");
			for (File productCertFile : productCertFiles) {
				if (productCertFile.getName().endsWith("_.pem")) {
					log.info("Skipping the generated product cert '"+productCertFile+"' from those provided by @DataProvider name=getProductCertFilesData.");
					continue;
				}
				ll.add(Arrays.asList(new Object[]{productCertFile}));
			}
			
			// get all of the product certs from the subscription-manager-migration-data
			SSHCommandResult result = client.runCommandAndWait("rpm -ql subscription-manager-migration-data | grep .pem");
			for (String productCertFilePath : result.getStdout().split("\n")) {
				if (productCertFilePath.isEmpty()) continue;
				ll.add(Arrays.asList(new Object[]{new File(productCertFilePath)}));
			}
		}
		
		return ll;
	}
	
}

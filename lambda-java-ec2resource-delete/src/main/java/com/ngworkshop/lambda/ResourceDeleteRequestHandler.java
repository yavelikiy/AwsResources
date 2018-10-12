package com.ngworkshop.lambda;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import com.amazonaws.services.autoscaling.AmazonAutoScaling;
import com.amazonaws.services.autoscaling.AmazonAutoScalingClientBuilder;
import com.amazonaws.services.autoscaling.model.AutoScalingGroup;
import com.amazonaws.services.autoscaling.model.DeleteAutoScalingGroupRequest;
import com.amazonaws.services.autoscaling.model.DeleteLaunchConfigurationRequest;
import com.amazonaws.services.autoscaling.model.DescribeAutoScalingGroupsRequest;
import com.amazonaws.services.autoscaling.model.DescribeAutoScalingGroupsResult;
import com.amazonaws.services.autoscaling.model.DescribeLoadBalancersRequest;
import com.amazonaws.services.autoscaling.model.DescribeLoadBalancersResult;
import com.amazonaws.services.autoscaling.model.LoadBalancerState;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.dynamodbv2.model.DeleteItemRequest;
import com.amazonaws.services.dynamodbv2.model.ScanRequest;
import com.amazonaws.services.dynamodbv2.model.ScanResult;
import com.amazonaws.services.dynamodbv2.model.UpdateItemRequest;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder;
import com.amazonaws.services.ec2.model.*;
import com.amazonaws.services.elasticloadbalancingv2.AmazonElasticLoadBalancing;
import com.amazonaws.services.elasticloadbalancingv2.AmazonElasticLoadBalancingClientBuilder;
import com.amazonaws.services.elasticloadbalancingv2.model.DeleteLoadBalancerRequest;
import com.amazonaws.services.elasticloadbalancingv2.model.DeleteTargetGroupRequest;
import com.amazonaws.services.elasticloadbalancingv2.model.LoadBalancer;
import com.amazonaws.services.elasticloadbalancingv2.model.Tag;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestStreamHandler;

public class ResourceDeleteRequestHandler implements RequestStreamHandler {
	static final String AFI = "AFI";
	static final String AMI = "AMI";
	static final String BUNDLE_TASK = "BundleTask";
	static final String CUSTOMER_GATEWAY = "CustomerGateway";
	static final String DHCP_OPRIONS = "DHCPOptions";
	static final String ESB_SNAPSHOT = "ESBSnapshot";
	static final String ESBVolume = "ESBVolume";
	static final String ELASTIC_IP = "ElasticIp";
	static final String INSTANCE = "Instance";
	static final String INTERNET_GATEWAY = "InternetGateway";
	static final String LAUNCH_TEMPLATE = "LaunchTemplate";
	static final String NAT_GATEWAY = "NatGateway";
	static final String NETWORK_ACL = "NetworkAcl";
	static final String NETWORK_INTERFACE = "NetworkInterface";
	static final String RESERVED_INSTANCE = "ReservedInstance";
	static final String ROUTE_TABLE = "RouteTable";
	static final String SPOT_INSTANCE_REQUEST = "SpotInstanceRequest";
	static final String SECURITY_GROUP_EC2 = "SecurityGroupEC2";
	static final String SECURITY_GROUP_VPC = "SecurityGroupVPC";
	static final String SUBNET = "Subnet";
	static final String VIRTUAL_PRIVATE_GATEWAY = "VPGateway";
	static final String VPC = "VPC";
	static final String VPC_PEER = "VPCPeeringConnection";
	static final String VPN_CONNECTION = "VPN";

	public void handleRequest(InputStream in, OutputStream out, Context context) throws IOException {
		// TODO Auto-generated method stub
		// Retrieves the credentials from a AWSCrentials.properties file.
		// AWSCredentials credentials = null;
		// try {
		// File initialFile = new
		// File("src/main/resources/AwsCredentials.properties");
		// InputStream credentialsStream = new FileInputStream(initialFile);
		// credentials = new PropertiesCredentials(credentialsStream);
		// } catch (IOException e1) {
		// System.out.println("Credentials were not properly entered into
		// AwsCredentials.properties.");
		// System.out.println(e1.getMessage());
		// System.exit(-1);
		// }

		// Create the AmazonEC2 client so we can call various APIs.
		System.out.println("Start Resource deletion...");
		AmazonEC2 ec2 = AmazonEC2ClientBuilder.defaultClient();
		AmazonAutoScaling as;
		AmazonElasticLoadBalancing elb;
		AmazonDynamoDB db = AmazonDynamoDBClientBuilder.standard().build();

		boolean useTrail = System.getenv("USE_TRAIL") != null
				&& System.getenv("USE_TRAIL").compareToIgnoreCase("true") == 0;
		String region = System.getenv("REGION");
		String userTable = System.getenv("USER_TABLE");
		String resourceTable = System.getenv("RESOURCE_TABLE");
		String[] regions = { "us-east-1", "us-east-2", "us-west-2", "us-west-1", "ca-central-1", "eu-central-1",
				"eu-west-1", "eu-west-2", "eu-west-3", "sa-east-1", "ap-northeast-1", "ap-northeast-2", "ap-south-1",
				"ap-southeast-1", "ap-southeast-2" };
		Object[] extraData = new Object[1];
		String user = getExpiredUsers(db, userTable, regions, extraData);
		while (user != null) {
			boolean canDeactivate = true;
			if (region != null) {
				ec2 = AmazonEC2ClientBuilder.standard().withRegion(region).build();
				as = AmazonAutoScalingClientBuilder.standard().withRegion(region).build();
				elb = AmazonElasticLoadBalancingClientBuilder.standard().withRegion(region).build();
				canDeactivate = canDeactivate && handleRequestInRegion(ec2, as, elb, db, userTable, resourceTable, useTrail, region, user);
			} else {
				for (int i = 0; i < regions.length; i++) {
					if (regions[i] != null) {
						String item = regions[i];
						ec2 = AmazonEC2ClientBuilder.standard().withRegion(item).build();
						as = AmazonAutoScalingClientBuilder.standard().withRegion(item).build();
						elb = AmazonElasticLoadBalancingClientBuilder.standard().withRegion(item).build();
						if (handleRequestInRegion(ec2, as, elb, db, userTable, resourceTable, useTrail, item, user)) {
							regions[i] = null;
						} else {
							canDeactivate = false;
						}
						updateProcessedRegions(db, userTable, getProcessedRegionsMask(regions), user);
					}
				}
			}
			if (canDeactivate)
				deactivateUser(db, userTable, resourceTable, user);
			else
				setUserRetry(db, userTable, (int)extraData[0], user);
			String[] refreshRegions = { "us-east-1", "us-east-2", "us-west-2", "us-west-1", "ca-central-1", "eu-central-1",
					"eu-west-1", "eu-west-2", "eu-west-3", "sa-east-1", "ap-northeast-1", "ap-northeast-2", "ap-south-1",
					"ap-southeast-1", "ap-southeast-2" };
			regions = refreshRegions;	
			user = getExpiredUsers(db, userTable, regions, extraData);
		}

	}

	boolean handleRequestInRegion(AmazonEC2 ec2, AmazonAutoScaling as, AmazonElasticLoadBalancing elb,
			AmazonDynamoDB db, String userTable, String resourceTable, boolean useTrail, String region, String user) {
		System.out.println("Invoking function in " + region + " region.");
		ArrayList<String> vpcs = new ArrayList<String>();
		ArrayList<String> instances = new ArrayList<String>();
		ArrayList<String> keys = new ArrayList<String>();
		boolean canDeactivate = true;
		System.out.println("User: " + user);
		if (useTrail) {
			scanCloudTrail(ec2, userTable, user, vpcs, instances);
		} else {
			removeAutoScalingGroups(as, user);

			getVpcsByTag(ec2, user, vpcs);
			System.out.println("Vpcs: " + String.join(",", vpcs));
			getInstancesByTag(ec2, user, instances, keys);
			getInstancesInVpcs(ec2, vpcs, instances, keys);
			System.out.println("Instances: " + String.join(",", instances));
			terminateInstances(ec2, instances);
			// removeScheduledInstances(ec2, vpcs, user);
			removeKeys(ec2, keys);
			removeNatGateways(ec2, vpcs);
			removeNatGateways(ec2, user);
			removeVpcEndpoints(ec2, vpcs);
			removeVpcPeeringConnections(ec2, vpcs);
			removeVpcPeeringConnections(ec2, user);
			removeRoutes(ec2, vpcs);
			removeRoutes(ec2, user);
			removeSubnets(ec2, vpcs);
			removeSubnets(ec2, user);
			removeInternetGateways(ec2, vpcs);
			removeInternetGateways(ec2, user);
			removeCustomerGateways(ec2, user);
			removeEgressOnlyInternetGateways(ec2, vpcs);
			removeVpnGateways(ec2, vpcs);
			removeVpnGateways(ec2, user);
			removeVpnConnections(ec2, user);
			removeSecurityGroups(ec2, vpcs);
			removeSecurityGroups(ec2, user);
			removeNetworkAcls(ec2, vpcs);
			removeNetworkAcls(ec2, user);
			removeNetworkInterfaces(ec2, vpcs);
			removeNetworkInterfaces(ec2, user);
			canDeactivate = removeVpcs(ec2, vpcs);

			removeFpgaImages(ec2, vpcs, user);
			removeImages(ec2, vpcs, user);
			removeLoadBalancers(elb, vpcs, user);
			removeTargetGroups(elb, user);
			// removeBundleTasks(ec2, vpcs, user);
			removeDhcpOptions(ec2, vpcs, user);
			removeVolumes(ec2, vpcs, user);
			removeSnapshots(ec2, vpcs, user);
			removeAddresses(ec2, vpcs, user);
			// removeLaunchTemplates(ec2, vpcs);
			removeLaunchTemplates(ec2, user);
			// removeSpotInstanceRequests(ec2, vpcs, user);
			removeUserKeyPairs(db, ec2, resourceTable, region, user);
			removeLaunchConfigurations(db, as, resourceTable, region, user);
			removePlacementGroups(db, ec2, resourceTable, region, user);
			// removeHosts(db, ec2, resourceTable, region, user);

			if (canDeactivate)
				deleteUserData(db, userTable, resourceTable, region, user);
		}
		return canDeactivate;
	}

	String getExpiredUsers(AmazonDynamoDB db, String table, String[] regions, Object[] extraData) {
		HashMap<String, AttributeValue> values = new HashMap<String, AttributeValue>();
		values.put(":end", (new AttributeValue()).withN((new Date()).getTime() + ""));
		values.put(":a", (new AttributeValue()).withBOOL(true));
		ScanRequest request = new ScanRequest().withTableName(table)
				.withFilterExpression("ExpirationDate < :end AND Active = :a").withExpressionAttributeValues(values);
		ScanResult response = db.scan(request);
		String oldestUser = null;
		String processedRegions = null;

		long oldestDate = -1;
		for (Map<String, AttributeValue> item : response.getItems()) {
			long date = new Long(item.get("ExpirationDate").getN());
			long retryDate = item.get("NextRetryDate") == null ? -1 : new Long(item.get("NextRetryDate").getN());
			int retryCount = item.get("RetryCount") == null ? 0 : new Integer(item.get("RetryCount").getN());
			extraData[0] = retryCount;
			date = date > retryDate ? date : retryDate;
			if (oldestDate < 0 || oldestDate > date) {
				oldestUser = item.get("Email").getS();
				processedRegions = item.get("ProcessedRegions") == null ? null : item.get("ProcessedRegions").getS();
				oldestDate = date;
			}
		}
		if (oldestUser != null) {
			if (processedRegions == null) {
				processedRegions = "";
				for (int i = 0; i < regions.length; i++)
					processedRegions += "0";
			}

			for (int i = 0; i < regions.length; i++) {
				if (processedRegions.charAt(i) == '1')
					regions[i] = null;
			}
		}

		return oldestUser;
	}

	String getProcessedRegionsMask(String[] regions) {
		String processedRegions = "";
		for (int i = 0; i < regions.length; i++)
			if (regions[i] == null)
				processedRegions += "1";
			else
				processedRegions += "0";
		return processedRegions;
	}

	void removeUserKeyPairs(AmazonDynamoDB db, AmazonEC2 ec2, String table, String region, String user) {
		System.out.println("Removing key pairs...");
		HashMap<String, AttributeValue> values = new HashMap<String, AttributeValue>();
		values.put(":r", (new AttributeValue()).withS(region));
		values.put(":u", (new AttributeValue()).withS(user));
		values.put(":t", (new AttributeValue()).withS("AWS::EC2::KeyPair"));
		HashMap<String, String> keys = new HashMap<String, String>();
		keys.put("#U", "User");
		ScanRequest request = new ScanRequest().withTableName(table)
				.withFilterExpression("ResourceRegion = :r AND ResourceType = :t AND #U = :u")
				.withExpressionAttributeValues(values).withExpressionAttributeNames(keys);
		ScanResult response = db.scan(request);
		for (Map<String, AttributeValue> item : response.getItems()) {
			String keyName = item.get("ResourceId").getS();
			try {
				DeleteKeyPairRequest request2 = new DeleteKeyPairRequest();
				request2.withKeyName(keyName);
				ec2.deleteKeyPair(request2);
				System.out.println("KeyPair name=" + keyName + " end");
			} catch (Exception e) {
				System.out.println(e.getMessage());
			}
		}
		System.out.println();
	}

	void removeHosts(AmazonDynamoDB db, AmazonEC2 ec2, String table, String region, String user) {
		System.out.println("Removing hosts...");
		HashMap<String, AttributeValue> values = new HashMap<String, AttributeValue>();
		values.put(":r", (new AttributeValue()).withS(region));
		values.put(":u", (new AttributeValue()).withS(user));
		values.put(":t", (new AttributeValue()).withS("AWS::EC2::Host"));
		HashMap<String, String> keys = new HashMap<String, String>();
		keys.put("#U", "User");
		ScanRequest request = new ScanRequest().withTableName(table)
				.withFilterExpression("ResourceRegion = :r AND ResourceType = :t AND #U = :u")
				.withExpressionAttributeValues(values).withExpressionAttributeNames(keys);
		ScanResult response = db.scan(request);
		for (Map<String, AttributeValue> item : response.getItems()) {
			String keyName = item.get("ResourceId").getS();
			try {
				DeleteKeyPairRequest request2 = new DeleteKeyPairRequest();
				request2.withKeyName(keyName);
				ec2.deleteKeyPair(request2);
				System.out.println("Host name=" + keyName);
			} catch (Exception e) {
				System.out.println(e.getMessage());
			}
		}
		System.out.println();
	}

	void removeLaunchConfigurations(AmazonDynamoDB db, AmazonAutoScaling as, String table, String region, String user) {
		System.out.println("Removing launch configurations...");
		HashMap<String, AttributeValue> values = new HashMap<String, AttributeValue>();
		values.put(":r", (new AttributeValue()).withS(region));
		values.put(":u", (new AttributeValue()).withS(user));
		values.put(":t", (new AttributeValue()).withS("AWS::AutoScaling::LaunchConfiguration"));
		HashMap<String, String> keys = new HashMap<String, String>();
		keys.put("#U", "User");
		ScanRequest request = new ScanRequest().withTableName(table)
				.withFilterExpression("ResourceRegion = :r AND ResourceType = :t AND #U = :u")
				.withExpressionAttributeValues(values).withExpressionAttributeNames(keys);
		ScanResult response = db.scan(request);
		for (Map<String, AttributeValue> item : response.getItems()) {
			String launchName = item.get("ResourceId").getS();
			try {
				DeleteLaunchConfigurationRequest request2 = new DeleteLaunchConfigurationRequest();
				request2.withLaunchConfigurationName(launchName);
				as.deleteLaunchConfiguration(request2);
				System.out.println("LaunchConfiguration name=" + launchName);
			} catch (Exception e) {
				System.out.println(e.getMessage());
			}
		}
		System.out.println();
	}

	void removePlacementGroups(AmazonDynamoDB db, AmazonEC2 ec2, String table, String region, String user) {
		System.out.println("Removing launch configurations...");
		HashMap<String, AttributeValue> values = new HashMap<String, AttributeValue>();
		values.put(":r", (new AttributeValue()).withS(region));
		values.put(":u", (new AttributeValue()).withS(user));
		values.put(":t", (new AttributeValue()).withS("AWS::EC2::PlacementGroup"));
		HashMap<String, String> keys = new HashMap<String, String>();
		keys.put("#U", "User");
		ScanRequest request = new ScanRequest().withTableName(table)
				.withFilterExpression("ResourceRegion = :r AND ResourceType = :t AND #U = :u")
				.withExpressionAttributeValues(values).withExpressionAttributeNames(keys);
		ScanResult response = db.scan(request);
		for (Map<String, AttributeValue> item : response.getItems()) {
			String groupName = item.get("ResourceId").getS();
			try {
				DeletePlacementGroupRequest request2 = new DeletePlacementGroupRequest();
				request2.withGroupName(groupName);
				ec2.deletePlacementGroup(request2);
				System.out.println("PlacementGroup name=" + groupName);
			} catch (Exception e) {
				System.out.println(e.getMessage());
			}
		}
		System.out.println();
	}

	void scanCloudTrail(AmazonEC2 ec2, String table, String user, ArrayList<String> vpcs, ArrayList<String> instances) {

	}

	void getInstancesByTag(AmazonEC2 ec2, String user, ArrayList<String> instances, ArrayList<String> keys) {
		Filter filter = new Filter("tag:Owner");
		filter.withValues(user);
		DescribeInstancesRequest request = new DescribeInstancesRequest();
		request.withFilters(filter);
		DescribeInstancesResult result = ec2.describeInstances(request);
		for (Reservation item : result.getReservations()) {
			for (Instance inst : item.getInstances()) {
				instances.add(inst.getInstanceId());
				keys.add(inst.getKeyName());
			}
		}
		// if (System.getenv("TEST_USER") != null) {
		// instances.add("i-123");
		// }
	}

	void getInstancesInVpcs(AmazonEC2 ec2, ArrayList<String> vpcs, ArrayList<String> instances,
			ArrayList<String> keys) {
		Filter filter = new Filter("vpc-id");
		filter.withValues(vpcs);
		DescribeInstancesRequest request = new DescribeInstancesRequest();
		request.withFilters(filter);
		DescribeInstancesResult result = ec2.describeInstances(request);
		for (Reservation item : result.getReservations()) {
			for (Instance inst : item.getInstances()) {
				if (!arrayContains(instances, inst.getInstanceId())) {
					instances.add(inst.getInstanceId());
					keys.add(inst.getKeyName());
				}
			}
		}
	}

	void getVpcsByTag(AmazonEC2 ec2, String user, ArrayList<String> vpcs) {
		Filter filter = new Filter("tag:Owner");
		filter.withValues(user);
		DescribeVpcsRequest request = new DescribeVpcsRequest();
		request.withFilters(filter);
		DescribeVpcsResult result = ec2.describeVpcs(request);
		for (Vpc item : result.getVpcs()) {
			vpcs.add(item.getVpcId());
		}
	}

	void terminateInstances(AmazonEC2 ec2, ArrayList<String> ids) {
		System.out.println("Terminating instances...");
		for (String id : ids) {
			disableInstanceLock(ec2, id);
			terminateInstance(ec2, id);
		}
	}

	void removeKeys(AmazonEC2 ec2, ArrayList<String> keys) {
		System.out.println("Removing keys...");
		for (String key : keys) {
			try {
				DeleteKeyPairRequest request = new DeleteKeyPairRequest();
				request.withKeyName(key);
				ec2.deleteKeyPair(request);
			} catch (Exception e) {
				System.out.println(e.getMessage());
			}
		}
	}

	void terminateInstance(AmazonEC2 ec2, String id) {
		try {
			TerminateInstancesRequest request = new TerminateInstancesRequest();
			request.withInstanceIds(id);
			TerminateInstancesResult result = ec2.terminateInstances(request);
			String stateAfter = result.getTerminatingInstances().get(0).getCurrentState().getName();
			if (stateAfter.compareToIgnoreCase("terminating") != 0
					&& stateAfter.compareToIgnoreCase("terminated") != 0) {
				System.out.println("Instance not terminated: " + id + " state " + stateAfter);
			}

		} catch (Exception e) {
			System.out.println(e.getMessage());
		}

	}

	void disableInstanceLock(AmazonEC2 ec2, String id) {
		try {
			ModifyInstanceAttributeRequest request = new ModifyInstanceAttributeRequest();
			request.withInstanceId(id).withDisableApiTermination(false);
			ec2.modifyInstanceAttribute(request);
		} catch (Exception e) {
			System.out.println(e.getMessage());
		}

	}

	void removeElastics(AmazonEC2 ec2, ArrayList<String> instances) {

	}

	void removeNatGateways(AmazonEC2 ec2, ArrayList<String> vpcs) {
		System.out.print("Removing NatGateways...");
		// get id
		DescribeNatGatewaysRequest dRequest = new DescribeNatGatewaysRequest();
		Filter filter = new Filter();
		filter.withName("vpc-id").withValues(vpcs);
		dRequest.withFilter(filter);
		DescribeNatGatewaysResult dResult = ec2.describeNatGateways(dRequest);
		for (NatGateway item : dResult.getNatGateways()) {

			try {
				// remove by id
				DeleteNatGatewayRequest request = new DeleteNatGatewayRequest();
				System.out.print(" " + item.getNatGatewayId() + ";");
				request.withNatGatewayId(item.getNatGatewayId());
				ec2.deleteNatGateway(request);
			} catch (Exception e) {
				System.out.println(e.getMessage());
			}
		}
		System.out.println();
	}

	void removeNatGateways(AmazonEC2 ec2, String user) {
		System.out.print("Removing NatGateways by tag...");
		// get id
		DescribeNatGatewaysRequest dRequest = new DescribeNatGatewaysRequest();
		Filter filter = new Filter();
		filter.withName("tag:Owner").withValues(user);
		dRequest.withFilter(filter);
		DescribeNatGatewaysResult dResult = ec2.describeNatGateways(dRequest);
		for (NatGateway item : dResult.getNatGateways()) {

			try {
				// remove by id
				DeleteNatGatewayRequest request = new DeleteNatGatewayRequest();
				System.out.print(" " + item.getNatGatewayId() + ";");
				request.withNatGatewayId(item.getNatGatewayId());
				ec2.deleteNatGateway(request);
			} catch (Exception e) {
				System.out.println(e.getMessage());
			}
		}
		System.out.println();
	}

	void removeFpgaImages(AmazonEC2 ec2, ArrayList<String> vpcs, String user) {
		System.out.print("Removing FpgaImages...");
		// get id
		try {
			DescribeFpgaImagesRequest dRequest = new DescribeFpgaImagesRequest();
			Filter filter = new Filter();
			filter.withName("tag:Owner").withValues(user);
			dRequest.withFilters(filter);
			DescribeFpgaImagesResult dResult = ec2.describeFpgaImages(dRequest);
			for (FpgaImage item : dResult.getFpgaImages()) {

				try {
					// remove by id
					DeleteFpgaImageRequest request = new DeleteFpgaImageRequest();
					System.out.print(" " + item.getFpgaImageId() + ";");
					request.withFpgaImageId(item.getFpgaImageId());
					ec2.deleteFpgaImage(request);
				} catch (Exception e) {
					System.out.println(e.getMessage());
				}
			}
		} catch (Exception e) {
			System.out.println(e.getMessage());
		}
		System.out.println();
	}

	void removeAddresses(AmazonEC2 ec2, ArrayList<String> vpcs, String user) {
		System.out.print("Removing Addresses...");
		// get id
		DescribeAddressesRequest dRequest = new DescribeAddressesRequest();
		Filter filter = new Filter();
		filter.withName("tag:Owner").withValues(user);
		dRequest.withFilters(filter);
		DescribeAddressesResult dResult = ec2.describeAddresses(dRequest);
		for (Address item : dResult.getAddresses()) {

			try {
				// remove by id
				if (item.getAssociationId() != null) {
					DisassociateAddressRequest request = new DisassociateAddressRequest();
					if (item.getPublicIp() != null)
						request.withPublicIp(item.getPublicIp());
					request.withAssociationId(item.getAssociationId());
					ec2.disassociateAddress(request);
				}
			} catch (Exception e) {
				System.out.println(e.getMessage());
			}

			try {
				// remove by id
				ReleaseAddressRequest request = new ReleaseAddressRequest();
				if (item.getAllocationId() != null)
					request.withAllocationId(item.getAllocationId());
				else if (item.getPublicIp() != null)
					request.withPublicIp(item.getPublicIp());
				System.out.print(" " + item.getAllocationId() + ";");
				ec2.releaseAddress(request);
			} catch (Exception e) {
				System.out.println(e.getMessage());
			}
		}
		System.out.println();
	}

	void removeSnapshots(AmazonEC2 ec2, ArrayList<String> vpcs, String user) {
		System.out.print("Removing Snapshots...");
		// get id
		DescribeSnapshotsRequest dRequest = new DescribeSnapshotsRequest();
		Filter filter = new Filter();
		filter.withName("tag:Owner").withValues(user);
		dRequest.withFilters(filter);
		DescribeSnapshotsResult dResult = ec2.describeSnapshots(dRequest);
		for (Snapshot item : dResult.getSnapshots()) {

			try {
				// remove by id
				DeleteSnapshotRequest request = new DeleteSnapshotRequest();
				System.out.print(" " + item.getSnapshotId() + ";");
				request.withSnapshotId(item.getSnapshotId());
				ec2.deleteSnapshot(request);
			} catch (Exception e) {
				System.out.println(e.getMessage());
			}
		}
		System.out.println();
	}

	void removeVolumes(AmazonEC2 ec2, ArrayList<String> vpcs, String user) {
		System.out.print("Removing Volumes...");
		// get id
		try {
			DescribeVolumesRequest dRequest = new DescribeVolumesRequest();
			Filter filter = new Filter();
			filter.withName("tag:Owner").withValues(user);
			dRequest.withFilters(filter);
			DescribeVolumesResult dResult = ec2.describeVolumes(dRequest);
			for (Volume item : dResult.getVolumes()) {

				try {
					// remove by id
					DeleteVolumeRequest request = new DeleteVolumeRequest();
					System.out.print(" " + item.getVolumeId() + ";");
					request.withVolumeId(item.getVolumeId());
					ec2.deleteVolume(request);
				} catch (Exception e) {
					System.out.println(e.getMessage());
				}
			}
		} catch (Exception e) {
			System.out.println(e.getMessage());
		}
		System.out.println();
	}

	void removeDhcpOptions(AmazonEC2 ec2, ArrayList<String> vpcs, String user) {
		System.out.print("Removing DhcpOptions...");
		// get id
		DescribeDhcpOptionsRequest dRequest = new DescribeDhcpOptionsRequest();
		Filter filter = new Filter();
		filter.withName("tag:Owner").withValues(user);
		dRequest.withFilters(filter);
		DescribeDhcpOptionsResult dResult = ec2.describeDhcpOptions(dRequest);
		for (DhcpOptions item : dResult.getDhcpOptions()) {

			try {
				// remove by id
				DeleteDhcpOptionsRequest request = new DeleteDhcpOptionsRequest();
				System.out.print(" " + item.getDhcpOptionsId() + ";");
				request.withDhcpOptionsId(item.getDhcpOptionsId());
				ec2.deleteDhcpOptions(request);
			} catch (Exception e) {
				System.out.println(e.getMessage());
			}
		}
		System.out.println();
	}

	void removeImages(AmazonEC2 ec2, ArrayList<String> vpcs, String user) {
		System.out.print("Removing Images...");
		// get id
		DescribeImagesRequest dRequest = new DescribeImagesRequest();
		Filter filter = new Filter();
		filter.withName("tag:Owner").withValues(user);
		dRequest.withFilters(filter);
		DescribeImagesResult dResult = ec2.describeImages(dRequest);
		for (Image item : dResult.getImages()) {

			try {
				// remove by id
				DeregisterImageRequest request = new DeregisterImageRequest();
				System.out.println(" " + item.getImageId() + ";");
				request.withImageId(item.getImageId());
				ec2.deregisterImage(request);
			} catch (Exception e) {
				System.out.println(e.getMessage());
			}
		}
		System.out.println();
	}

	void removeAutoScalingGroups(AmazonAutoScaling as, String user) {
		System.out.print("Removing AutoScalingGroups...");
		String nextToken = null;
		// get id
		do {
			DescribeAutoScalingGroupsRequest dRequest = new DescribeAutoScalingGroupsRequest();
			if (nextToken != null)
				dRequest.setNextToken(nextToken);
			DescribeAutoScalingGroupsResult dResult = as.describeAutoScalingGroups();

			nextToken = dResult.getNextToken();
			for (AutoScalingGroup item : dResult.getAutoScalingGroups()) {
				try {
					for (com.amazonaws.services.autoscaling.model.TagDescription tag : item.getTags()) {
						// remove by id
						if (tag.getKey().compareTo("Owner") == 0 && tag.getValue().compareTo(user) == 0) {
							DeleteAutoScalingGroupRequest request = new DeleteAutoScalingGroupRequest();
							System.out.println(" " + item.getAutoScalingGroupName() + ";");
							request.withAutoScalingGroupName(item.getAutoScalingGroupName());
							as.deleteAutoScalingGroup(request);
							break;
						}
					}
				} catch (Exception e) {
					System.out.println(e.getMessage());
				}
			}
		} while (nextToken != null);
		System.out.println();
	}

	void removeLoadBalancers(AmazonElasticLoadBalancing elb, ArrayList<String> vpcs, String user) {
		System.out.print("Removing LoadBalancers and TargetGroups...");
		String nextTokenBalancer = null;
		// get id
		do {
			com.amazonaws.services.elasticloadbalancingv2.model.DescribeLoadBalancersRequest r1 = new com.amazonaws.services.elasticloadbalancingv2.model.DescribeLoadBalancersRequest();
			if (nextTokenBalancer != null)
				r1.setMarker(nextTokenBalancer);
			com.amazonaws.services.elasticloadbalancingv2.model.DescribeLoadBalancersResult res1 = elb
					.describeLoadBalancers(r1);
			nextTokenBalancer = res1.getNextMarker();
			ArrayList<String> resourceArns = new ArrayList<String>();
			if (!res1.getLoadBalancers().isEmpty()) {
				for (LoadBalancer lb : res1.getLoadBalancers()) {
					if ( vpcs.stream().anyMatch(str -> str.compareTo(lb.getVpcId()) == 0)) {
						DeleteLoadBalancerRequest request = new DeleteLoadBalancerRequest();
						System.out.println(" " + lb.getLoadBalancerArn() + ";");
						request.withLoadBalancerArn(lb.getLoadBalancerArn());
						elb.deleteLoadBalancer(request);
					}else
						resourceArns.add(lb.getLoadBalancerArn());
				}
				com.amazonaws.services.elasticloadbalancingv2.model.DescribeTagsRequest dRequest = new com.amazonaws.services.elasticloadbalancingv2.model.DescribeTagsRequest();
				dRequest.withResourceArns(resourceArns);
				com.amazonaws.services.elasticloadbalancingv2.model.DescribeTagsResult dResult = elb
						.describeTags(dRequest);

				for (com.amazonaws.services.elasticloadbalancingv2.model.TagDescription tagDesc : dResult
						.getTagDescriptions()) {
					try {
						for (Tag tag : tagDesc.getTags()) {
							// remove by id
							if (tag.getKey().compareTo("Owner") == 0 && tag.getValue().compareTo(user) == 0) {
								if (tagDesc.getResourceArn().contains("targetgroup")) {
									DeleteTargetGroupRequest request = new DeleteTargetGroupRequest();
									System.out.println(" " + tagDesc.getResourceArn() + ";");
									request.withTargetGroupArn(tagDesc.getResourceArn());
									elb.deleteTargetGroup(request);
								} else {
									DeleteLoadBalancerRequest request = new DeleteLoadBalancerRequest();
									System.out.println(" " + tagDesc.getResourceArn() + ";");
									request.withLoadBalancerArn(tagDesc.getResourceArn());
									elb.deleteLoadBalancer(request);
								}
								break;
							}
						}
					} catch (Exception e) {
						System.out.println(e.getMessage());
					}
				}
			}
		} while (nextTokenBalancer != null);
		System.out.println();

	}

	void removeTargetGroups(AmazonElasticLoadBalancing elb, String user) {
		System.out.print("Removing TargetGroups and TargetGroups...");
		String nextTokenBalancer = null;
		// get id
		do {
			com.amazonaws.services.elasticloadbalancingv2.model.DescribeTargetGroupsRequest r1 = new com.amazonaws.services.elasticloadbalancingv2.model.DescribeTargetGroupsRequest();
			if (nextTokenBalancer != null)
				r1.setMarker(nextTokenBalancer);
			com.amazonaws.services.elasticloadbalancingv2.model.DescribeTargetGroupsResult res1 = elb
					.describeTargetGroups(r1);
			nextTokenBalancer = res1.getNextMarker();
			ArrayList<String> resourceArns = new ArrayList<String>();
			if (!res1.getTargetGroups().isEmpty()) {
				for (com.amazonaws.services.elasticloadbalancingv2.model.TargetGroup lb : res1.getTargetGroups()) {
					resourceArns.add(lb.getTargetGroupArn());

				}
				com.amazonaws.services.elasticloadbalancingv2.model.DescribeTagsRequest dRequest = new com.amazonaws.services.elasticloadbalancingv2.model.DescribeTagsRequest();
				dRequest.withResourceArns(resourceArns);
				com.amazonaws.services.elasticloadbalancingv2.model.DescribeTagsResult dResult = elb
						.describeTags(dRequest);

				for (com.amazonaws.services.elasticloadbalancingv2.model.TagDescription tagDesc : dResult
						.getTagDescriptions()) {
					try {
						for (Tag tag : tagDesc.getTags()) {
							// remove by id
							if (tag.getKey().compareTo("Owner") == 0 && tag.getValue().compareTo(user) == 0) {
								DeleteTargetGroupRequest request = new DeleteTargetGroupRequest();
								System.out.println(" " + tagDesc.getResourceArn() + ";");
								request.withTargetGroupArn(tagDesc.getResourceArn());
								elb.deleteTargetGroup(request);
								break;
							}
						}
					} catch (Exception e) {
						System.out.println(e.getMessage());
					}
				}
			}
		} while (nextTokenBalancer != null);
		System.out.println();

	}

	void removeNetworkInterfaces(AmazonEC2 ec2, ArrayList<String> vpcs) {
		System.out.print("Removing NetworkInterfaces...");
		// get id
		DescribeNetworkInterfacesRequest dRequest = new DescribeNetworkInterfacesRequest();
		Filter filter = new Filter();
		filter.withName("vpc-id").withValues(vpcs);
		dRequest.withFilters(filter);
		DescribeNetworkInterfacesResult dResult = ec2.describeNetworkInterfaces(dRequest);
		for (NetworkInterface item : dResult.getNetworkInterfaces()) {

			try {
				// remove by id
				DeleteNetworkInterfaceRequest request = new DeleteNetworkInterfaceRequest();
				System.out.print(" " + item.getNetworkInterfaceId() + ";");
				request.withNetworkInterfaceId(item.getNetworkInterfaceId());
				ec2.deleteNetworkInterface(request);
			} catch (Exception e) {
				System.out.println(e.getMessage());
			}
		}
		System.out.println();
	}

	void removeNetworkInterfaces(AmazonEC2 ec2, String user) {
		System.out.print("Removing NetworkInterfaces by tag...");
		// get id
		DescribeNetworkInterfacesRequest dRequest = new DescribeNetworkInterfacesRequest();
		Filter filter = new Filter();
		filter.withName("tag:Owner").withValues(user);
		dRequest.withFilters(filter);
		DescribeNetworkInterfacesResult dResult = ec2.describeNetworkInterfaces(dRequest);
		for (NetworkInterface item : dResult.getNetworkInterfaces()) {

			try {
				// remove by id
				DeleteNetworkInterfaceRequest request = new DeleteNetworkInterfaceRequest();
				System.out.print(" " + item.getNetworkInterfaceId() + ";");
				request.withNetworkInterfaceId(item.getNetworkInterfaceId());
				ec2.deleteNetworkInterface(request);
			} catch (Exception e) {
				System.out.println(e.getMessage());
			}
		}
		System.out.println();
	}

	void removeLaunchTemplates(AmazonEC2 ec2, ArrayList<String> vpcs) {
		System.out.print("Removing LaunchTemplates...");
		// get id
		DescribeLaunchTemplatesRequest dRequest = new DescribeLaunchTemplatesRequest();
		Filter filter = new Filter();
		filter.withName("vpc-id").withValues(vpcs);
		dRequest.withFilters(filter);
		DescribeLaunchTemplatesResult dResult = ec2.describeLaunchTemplates(dRequest);
		for (LaunchTemplate item : dResult.getLaunchTemplates()) {

			try {
				// remove by id
				DeleteLaunchTemplateRequest request = new DeleteLaunchTemplateRequest();
				System.out.print(" " + item.getLaunchTemplateId() + ";");
				request.withLaunchTemplateId(item.getLaunchTemplateId());
				ec2.deleteLaunchTemplate(request);
			} catch (Exception e) {
				System.out.println(e.getMessage());
			}
		}
		System.out.println();
	}

	void removeLaunchTemplates(AmazonEC2 ec2, String user) {
		System.out.print("Removing LaunchTemplates by tag...");
		// get id
		DescribeLaunchTemplatesRequest dRequest = new DescribeLaunchTemplatesRequest();
		Filter filter = new Filter();
		filter.withName("tag:Owner").withValues(user);
		dRequest.withFilters(filter);
		DescribeLaunchTemplatesResult dResult = ec2.describeLaunchTemplates(dRequest);
		for (LaunchTemplate item : dResult.getLaunchTemplates()) {

			try {
				// remove by id
				DeleteLaunchTemplateRequest request = new DeleteLaunchTemplateRequest();
				System.out.print(" " + item.getLaunchTemplateId() + ";");
				request.withLaunchTemplateId(item.getLaunchTemplateId());
				ec2.deleteLaunchTemplate(request);
			} catch (Exception e) {
				System.out.println(e.getMessage());
			}
		}
		System.out.println();
	}

	void removeSpotInstanceRequests(AmazonEC2 ec2, ArrayList<String> vpcs, String user) {
		System.out.print("Removing SpotInstanceRequests...");
		// get id
		DescribeSpotInstanceRequestsRequest dRequest = new DescribeSpotInstanceRequestsRequest();
		Filter filter = new Filter();
		filter.withName("tag:Owner").withValues(user);
		dRequest.withFilters(filter);
		DescribeSpotInstanceRequestsResult dResult = ec2.describeSpotInstanceRequests(dRequest);
		for (SpotInstanceRequest item : dResult.getSpotInstanceRequests()) {

			try {
				// remove by id
				CancelSpotInstanceRequestsRequest request = new CancelSpotInstanceRequestsRequest();
				System.out.print(" " + item.getSpotInstanceRequestId() + ";");
				request.withSpotInstanceRequestIds(item.getSpotInstanceRequestId());
				ec2.cancelSpotInstanceRequests(request);
			} catch (Exception e) {
				System.out.println(e.getMessage());
			}
		}
		System.out.println();
	}

	void removeScheduledInstances(AmazonEC2 ec2, ArrayList<String> vpcs, String user) {
		try {
			System.out.print("Removing ScheduledInstances...");
			// get id
			DescribeScheduledInstancesRequest dRequest = new DescribeScheduledInstancesRequest();
			Filter filter = new Filter();
			filter.withName("tag:Owner").withValues(user);
			dRequest.withFilters(filter);
			DescribeScheduledInstancesResult dResult = ec2.describeScheduledInstances(dRequest);
			for (ScheduledInstance item : dResult.getScheduledInstanceSet()) {

				try {
					// remove by id
					disableInstanceLock(ec2, item.getScheduledInstanceId());
					TerminateInstancesRequest request = new TerminateInstancesRequest();
					System.out.print(" " + item.getScheduledInstanceId() + ";");
					request.withInstanceIds(item.getScheduledInstanceId());
					ec2.terminateInstances(request);
				} catch (Exception e) {
					System.out.println(e.getMessage());
				}
			}
			System.out.println();
		} catch (Exception e) {
			System.out.println(e.getMessage());
		}
	}

	void removeBundleTasks(AmazonEC2 ec2, ArrayList<String> vpcs, String user) {
		System.out.print("Removing BundleTasks...");
		// get id
		DescribeBundleTasksRequest dRequest = new DescribeBundleTasksRequest();
		Filter filter = new Filter();
		filter.withName("tag:Owner").withValues(user);
		dRequest.withFilters(filter);
		DescribeBundleTasksResult dResult = ec2.describeBundleTasks(dRequest);
		for (BundleTask item : dResult.getBundleTasks()) {

			try {
				// remove by id
				CancelBundleTaskRequest request = new CancelBundleTaskRequest();
				System.out.print(" " + item.getBundleId() + ";");
				request.withBundleId(item.getBundleId());
				ec2.cancelBundleTask(request);
			} catch (Exception e) {
				System.out.println(e.getMessage());
			}
		}
		System.out.println();
	}

	void removeRoutes(AmazonEC2 ec2, ArrayList<String> vpcs) {
		System.out.print("Removing Routes...");
		// get id
		DescribeRouteTablesRequest dRequest = new DescribeRouteTablesRequest();
		Filter filter = new Filter();
		filter.withName("vpc-id").withValues(vpcs);
		dRequest.withFilters(filter);
		DescribeRouteTablesResult dResult = ec2.describeRouteTables();
		for (RouteTable item : dResult.getRouteTables()) {

			for (RouteTableAssociation rta : item.getAssociations()) {
				if (rta.isMain()) {
					try {
						// remove by id
						ReplaceRouteTableAssociationRequest request = new ReplaceRouteTableAssociationRequest();
						request.withAssociationId(rta.getRouteTableAssociationId())
								.withRouteTableId(item.getRouteTableId());
						ec2.replaceRouteTableAssociation(request);
					} catch (Exception e) {
						System.out.println(e.getMessage());
					}

				} else {
					try {
						// remove by id
						DisassociateRouteTableRequest request = new DisassociateRouteTableRequest();
						request.withAssociationId(rta.getRouteTableAssociationId());
						ec2.disassociateRouteTable(request);
					} catch (Exception e) {
						System.out.println(e.getMessage());
					}
				}
			}

			try {
				// remove by id
				DeleteRouteTableRequest request = new DeleteRouteTableRequest();
				System.out.print(" " + item.getRouteTableId() + ";");
				request.withRouteTableId(item.getRouteTableId());
				ec2.deleteRouteTable(request);
			} catch (Exception e) {
				System.out.println(e.getMessage());
			}
		}
		System.out.println();
	}

	void removeRoutes(AmazonEC2 ec2, String user) {
		System.out.print("Removing Routes by tag...");
		// get id
		DescribeRouteTablesRequest dRequest = new DescribeRouteTablesRequest();
		Filter filter = new Filter();
		filter.withName("tag:Owner").withValues(user);
		dRequest.withFilters(filter);
		DescribeRouteTablesResult dResult = ec2.describeRouteTables();
		for (RouteTable item : dResult.getRouteTables()) {

			for (RouteTableAssociation rta : item.getAssociations()) {
				if (rta.isMain()) {
					try {
						// remove by id
						ReplaceRouteTableAssociationRequest request = new ReplaceRouteTableAssociationRequest();
						request.withAssociationId(rta.getRouteTableAssociationId())
								.withRouteTableId(item.getRouteTableId());
						ec2.replaceRouteTableAssociation(request);
					} catch (Exception e) {
						System.out.println(e.getMessage());
					}

				} else {
					try {
						// remove by id
						DisassociateRouteTableRequest request = new DisassociateRouteTableRequest();
						request.withAssociationId(rta.getRouteTableAssociationId());
						ec2.disassociateRouteTable(request);
					} catch (Exception e) {
						System.out.println(e.getMessage());
					}
				}
			}

			try {
				// remove by id
				DeleteRouteTableRequest request = new DeleteRouteTableRequest();
				System.out.print(" " + item.getRouteTableId() + ";");
				request.withRouteTableId(item.getRouteTableId());
				ec2.deleteRouteTable(request);
			} catch (Exception e) {
				System.out.println(e.getMessage());
			}
		}
		System.out.println();
	}

	void removeSecurityGroups(AmazonEC2 ec2, ArrayList<String> vpcs) {
		System.out.print("Removing SecurityGroups...");
		// get id
		DescribeSecurityGroupsRequest dRequest = new DescribeSecurityGroupsRequest();
		Filter filter = new Filter();
		filter.withName("vpc-id").withValues(vpcs);
		dRequest.withFilters(filter);
		DescribeSecurityGroupsResult dResult = ec2.describeSecurityGroups(dRequest);
		for (SecurityGroup item : dResult.getSecurityGroups()) {

			try {
				// remove by id
				RevokeSecurityGroupEgressRequest request = new RevokeSecurityGroupEgressRequest();
				request.withGroupId(item.getGroupId()).withIpPermissions(item.getIpPermissionsEgress());
				ec2.revokeSecurityGroupEgress(request);
			} catch (Exception e) {
				System.out.println(e.getMessage());
			}

			try {
				// remove by id
				RevokeSecurityGroupIngressRequest request = new RevokeSecurityGroupIngressRequest();
				request.withGroupId(item.getGroupId()).withIpPermissions(item.getIpPermissions());
				ec2.revokeSecurityGroupIngress(request);
			} catch (Exception e) {
				System.out.println(e.getMessage());
			}

			try {
				// remove by id
				DeleteSecurityGroupRequest request = new DeleteSecurityGroupRequest();
				System.out.print(" " + item.getGroupId() + ";");
				request.withGroupId(item.getGroupId());
				ec2.deleteSecurityGroup(request);
			} catch (Exception e) {
				System.out.println(e.getMessage());
			}
		}
		System.out.println();
	}

	void removeSecurityGroups(AmazonEC2 ec2, String user) {
		System.out.print("Removing SecurityGroups by tag...");
		// get id
		DescribeSecurityGroupsRequest dRequest = new DescribeSecurityGroupsRequest();
		Filter filter = new Filter();
		filter.withName("tag:Owner").withValues(user);
		dRequest.withFilters(filter);
		DescribeSecurityGroupsResult dResult = ec2.describeSecurityGroups(dRequest);
		for (SecurityGroup item : dResult.getSecurityGroups()) {

			try {
				// remove by id
				RevokeSecurityGroupEgressRequest request = new RevokeSecurityGroupEgressRequest();
				request.withGroupId(item.getGroupId()).withIpPermissions(item.getIpPermissionsEgress());
				ec2.revokeSecurityGroupEgress(request);
			} catch (Exception e) {
				System.out.println(e.getMessage());
			}

			try {
				// remove by id
				RevokeSecurityGroupIngressRequest request = new RevokeSecurityGroupIngressRequest();
				request.withGroupId(item.getGroupId()).withIpPermissions(item.getIpPermissions());
				ec2.revokeSecurityGroupIngress(request);
			} catch (Exception e) {
				System.out.println(e.getMessage());
			}

			try {
				// remove by id
				DeleteSecurityGroupRequest request = new DeleteSecurityGroupRequest();
				System.out.print(" " + item.getGroupId() + ";");
				request.withGroupId(item.getGroupId());
				ec2.deleteSecurityGroup(request);
			} catch (Exception e) {
				System.out.println(e.getMessage());
			}
		}
		System.out.println();
	}

	void removeVpcPeeringConnections(AmazonEC2 ec2, ArrayList<String> vpcs) {
		System.out.print("Removing VpcPeeringConnections...");
		// get id
		DescribeVpcPeeringConnectionsRequest dRequest = new DescribeVpcPeeringConnectionsRequest();
		Filter filter = new Filter();
		filter.withName("requester-vpc-info.vpc-id").withValues(vpcs);
		dRequest.withFilters(filter);
		DescribeVpcPeeringConnectionsResult dResult = ec2.describeVpcPeeringConnections(dRequest);
		for (VpcPeeringConnection item : dResult.getVpcPeeringConnections()) {

			try {
				// remove by id
				DeleteVpcPeeringConnectionRequest request = new DeleteVpcPeeringConnectionRequest();
				System.out.print(" " + item.getVpcPeeringConnectionId() + ";");
				request.withVpcPeeringConnectionId(item.getVpcPeeringConnectionId());
				ec2.deleteVpcPeeringConnection(request);
			} catch (Exception e) {
				System.out.println(e.getMessage());
			}
		}

		filter = new Filter();
		filter.withName("accepter-vpc-info.vpc-id").withValues(vpcs);
		dRequest.withFilters(filter);
		dResult = ec2.describeVpcPeeringConnections(dRequest);
		for (VpcPeeringConnection item : dResult.getVpcPeeringConnections()) {

			try {
				// remove by id
				DeleteVpcPeeringConnectionRequest request = new DeleteVpcPeeringConnectionRequest();
				System.out.print(" " + item.getVpcPeeringConnectionId() + ";");
				request.withVpcPeeringConnectionId(item.getVpcPeeringConnectionId());
				ec2.deleteVpcPeeringConnection(request);
			} catch (Exception e) {
				System.out.println(e.getMessage());
			}
		}
		System.out.println();
	}

	void removeVpcPeeringConnections(AmazonEC2 ec2, String user) {
		System.out.print("Removing VpcPeeringConnections by tag...");
		// get id
		DescribeVpcPeeringConnectionsRequest dRequest = new DescribeVpcPeeringConnectionsRequest();
		Filter filter = new Filter();
		filter.withName("tag:Owner").withValues(user);
		dRequest.withFilters(filter);
		DescribeVpcPeeringConnectionsResult dResult = ec2.describeVpcPeeringConnections(dRequest);
		for (VpcPeeringConnection item : dResult.getVpcPeeringConnections()) {

			try {
				// remove by id
				DeleteVpcPeeringConnectionRequest request = new DeleteVpcPeeringConnectionRequest();
				System.out.print(" " + item.getVpcPeeringConnectionId() + ";");
				request.withVpcPeeringConnectionId(item.getVpcPeeringConnectionId());
				ec2.deleteVpcPeeringConnection(request);
			} catch (Exception e) {
				System.out.println(e.getMessage());
			}
		}
		System.out.println();
	}

	void removeVpcEndpoints(AmazonEC2 ec2, ArrayList<String> vpcs) {
		System.out.print("Removing VpcEndpoints...");
		// get id
		DescribeVpcEndpointsRequest dRequest = new DescribeVpcEndpointsRequest();
		Filter filter = new Filter();
		filter.withName("vpc-endpoint-id").withValues(vpcs);
		dRequest.withFilters(filter);
		DescribeVpcEndpointsResult dResult = ec2.describeVpcEndpoints(dRequest);
		for (VpcEndpoint item : dResult.getVpcEndpoints()) {

			try {
				// remove by id
				DeleteVpcEndpointsRequest request = new DeleteVpcEndpointsRequest();
				System.out.print(" " + item.getVpcEndpointId() + ";");
				request.withVpcEndpointIds(item.getVpcEndpointId());
				ec2.deleteVpcEndpoints(request);
			} catch (Exception e) {
				System.out.println(e.getMessage());
			}
		}

		dRequest = new DescribeVpcEndpointsRequest();
		filter = new Filter();
		filter.withName("vpc-id").withValues(vpcs);
		dRequest.withFilters(filter);
		dResult = ec2.describeVpcEndpoints(dRequest);
		for (VpcEndpoint item : dResult.getVpcEndpoints()) {

			try {
				// remove by id
				DeleteVpcEndpointsRequest request = new DeleteVpcEndpointsRequest();
				System.out.print(" " + item.getVpcEndpointId() + ";");
				request.withVpcEndpointIds(item.getVpcEndpointId());
				ec2.deleteVpcEndpoints(request);
			} catch (Exception e) {
				System.out.println(e.getMessage());
			}
		}
		System.out.println();
	}

	void removeInternetGateways(AmazonEC2 ec2, ArrayList<String> vpcs) {
		System.out.print("Removing InternetGateways...");
		// get id
		DescribeInternetGatewaysRequest dRequest = new DescribeInternetGatewaysRequest();
		Filter filter = new Filter();
		filter.withName("attachment.vpc-id").withValues(vpcs);
		dRequest.withFilters(filter);
		DescribeInternetGatewaysResult dResult = ec2.describeInternetGateways(dRequest);
		for (InternetGateway item : dResult.getInternetGateways()) {
			for (InternetGatewayAttachment vpcAtt : item.getAttachments()) {
				try {
					// remove by id
					DetachInternetGatewayRequest detRequest = new DetachInternetGatewayRequest();
					detRequest.withInternetGatewayId(item.getInternetGatewayId()).withVpcId(vpcAtt.getVpcId());
					ec2.detachInternetGateway(detRequest);
				} catch (Exception e) {
					System.out.println(e.getMessage());
				}
			}
			try {
				// remove by id
				DeleteInternetGatewayRequest request = new DeleteInternetGatewayRequest();
				System.out.print(" " + item.getInternetGatewayId() + ";");
				request.withInternetGatewayId(item.getInternetGatewayId());
				ec2.deleteInternetGateway(request);
			} catch (Exception e) {
				System.out.println(e.getMessage());
			}
		}
		System.out.println();
	}

	void removeInternetGateways(AmazonEC2 ec2, String user) {
		System.out.print("Removing InternetGateways by tag...");
		// get id
		DescribeInternetGatewaysRequest dRequest = new DescribeInternetGatewaysRequest();
		Filter filter = new Filter();
		filter.withName("tag:Owner").withValues(user);
		dRequest.withFilters(filter);
		DescribeInternetGatewaysResult dResult = ec2.describeInternetGateways(dRequest);
		for (InternetGateway item : dResult.getInternetGateways()) {
			for (InternetGatewayAttachment vpcAtt : item.getAttachments()) {
				try {
					// remove by id
					DetachInternetGatewayRequest detRequest = new DetachInternetGatewayRequest();
					detRequest.withInternetGatewayId(item.getInternetGatewayId()).withVpcId(vpcAtt.getVpcId());
					ec2.detachInternetGateway(detRequest);
				} catch (Exception e) {
					System.out.println(e.getMessage());
				}
			}
			try {
				// remove by id
				DeleteInternetGatewayRequest request = new DeleteInternetGatewayRequest();
				System.out.print(" " + item.getInternetGatewayId() + ";");
				request.withInternetGatewayId(item.getInternetGatewayId());
				ec2.deleteInternetGateway(request);
			} catch (Exception e) {
				System.out.println(e.getMessage());
			}
		}
		System.out.println();
	}

	void removeCustomerGateways(AmazonEC2 ec2, String user) {
		System.out.print("Removing CustomerGateways by Tag...");
		// get id
		DescribeCustomerGatewaysRequest dRequest = new DescribeCustomerGatewaysRequest();
		Filter filter = new Filter();
		filter.withName("tag:Owner").withValues(user);
		dRequest.withFilters(filter);
		DescribeCustomerGatewaysResult dResult = ec2.describeCustomerGateways(dRequest);
		for (CustomerGateway item : dResult.getCustomerGateways()) {
			try {
				// remove by id
				DeleteCustomerGatewayRequest request = new DeleteCustomerGatewayRequest();
				System.out.print(" " + item.getCustomerGatewayId() + ";");
				request.withCustomerGatewayId(item.getCustomerGatewayId());
				ec2.deleteCustomerGateway(request);
			} catch (Exception e) {
				System.out.println(e.getMessage());
			}
		}
		System.out.println();
	}

	void removeCustomerGateways(AmazonEC2 ec2, ArrayList<String> gateways) {
		System.out.print("Removing CustomerGateways...");
		for (String item : gateways) {
			try {
				// remove by id
				DeleteCustomerGatewayRequest request = new DeleteCustomerGatewayRequest();
				System.out.print(" " + item + ";");
				request.withCustomerGatewayId(item);
				ec2.deleteCustomerGateway(request);
			} catch (Exception e) {
				System.out.println(e.getMessage());
			}
		}
		System.out.println();
	}

	void removeVpnConnections(AmazonEC2 ec2, ArrayList<String> vpnGateways) {
		System.out.print("Removing VpnConnections...");
		// get id
		DescribeVpnConnectionsRequest dRequest = new DescribeVpnConnectionsRequest();
		Filter filter = new Filter();
		filter.withName("vpn-gateway-id").withValues(vpnGateways);
		dRequest.withFilters(filter);
		DescribeVpnConnectionsResult dResult = ec2.describeVpnConnections(dRequest);
		ArrayList<String> gateways = new ArrayList<String>();
		for (VpnConnection item : dResult.getVpnConnections()) {
			gateways.add(item.getCustomerGatewayId());
			try {
				// remove by id
				DeleteVpnConnectionRequest request = new DeleteVpnConnectionRequest();
				System.out.print(" " + item.getVpnConnectionId() + ";");
				request.withVpnConnectionId(item.getVpnConnectionId());
				ec2.deleteVpnConnection(request);
			} catch (Exception e) {
				System.out.println(e.getMessage());
			}
		}
		System.out.println();

		// call remove CustomerGateways
		removeCustomerGateways(ec2, gateways);
	}

	void removeVpnConnections(AmazonEC2 ec2, String user) {
		System.out.print("Removing VpnConnections by tag...");
		// get id
		DescribeVpnConnectionsRequest dRequest = new DescribeVpnConnectionsRequest();
		Filter filter = new Filter();
		filter.withName("tag:Owner").withValues(user);
		dRequest.withFilters(filter);
		DescribeVpnConnectionsResult dResult = ec2.describeVpnConnections(dRequest);
		ArrayList<String> gateways = new ArrayList<String>();
		for (VpnConnection item : dResult.getVpnConnections()) {
			gateways.add(item.getCustomerGatewayId());
			try {
				// remove by id
				DeleteVpnConnectionRequest request = new DeleteVpnConnectionRequest();
				System.out.print(" " + item.getVpnConnectionId() + ";");
				request.withVpnConnectionId(item.getVpnConnectionId());
				ec2.deleteVpnConnection(request);
			} catch (Exception e) {
				System.out.println(e.getMessage());
			}
		}
		System.out.println();

		// call remove CustomerGateways
		removeCustomerGateways(ec2, gateways);
	}

	void removeNetworkAcls(AmazonEC2 ec2, ArrayList<String> vpcs) {
		System.out.print("Removing NetworkAcls...");
		// get id
		DescribeNetworkAclsRequest dRequest = new DescribeNetworkAclsRequest();
		Filter filter = new Filter();
		filter.withName("vpc-id").withValues(vpcs);
		dRequest.withFilters(filter);
		DescribeNetworkAclsResult dResult = ec2.describeNetworkAcls(dRequest);
		for (NetworkAcl item : dResult.getNetworkAcls()) {

			try {
				// remove by id
				DeleteNetworkAclRequest request = new DeleteNetworkAclRequest();
				System.out.print(" " + item.getNetworkAclId() + ";");
				request.withNetworkAclId(item.getNetworkAclId());
				ec2.deleteNetworkAcl(request);
			} catch (Exception e) {
				System.out.println(e.getMessage());
			}
		}
		System.out.println();
	}

	void removeNetworkAcls(AmazonEC2 ec2, String user) {
		System.out.print("Removing NetworkAcls by tag...");
		// get id
		DescribeNetworkAclsRequest dRequest = new DescribeNetworkAclsRequest();
		Filter filter = new Filter();
		filter.withName("tag:Owner").withValues(user);
		dRequest.withFilters(filter);
		DescribeNetworkAclsResult dResult = ec2.describeNetworkAcls(dRequest);
		for (NetworkAcl item : dResult.getNetworkAcls()) {

			try {
				// remove by id
				DeleteNetworkAclRequest request = new DeleteNetworkAclRequest();
				System.out.print(" " + item.getNetworkAclId() + ";");
				request.withNetworkAclId(item.getNetworkAclId());
				ec2.deleteNetworkAcl(request);
			} catch (Exception e) {
				System.out.println(e.getMessage());
			}
		}
		System.out.println();
	}

	void removeSubnets(AmazonEC2 ec2, ArrayList<String> vpcs) {
		System.out.print("Removing Subnets...");
		// get id
		DescribeSubnetsRequest dRequest = new DescribeSubnetsRequest();
		Filter filter = new Filter();
		filter.withName("vpc-id").withValues(vpcs);
		dRequest.withFilters(filter);
		DescribeSubnetsResult dResult = ec2.describeSubnets(dRequest);
		for (Subnet item : dResult.getSubnets()) {

			try {
				// remove by id
				DeleteSubnetRequest request = new DeleteSubnetRequest();
				System.out.print(" " + item.getSubnetId() + ";");
				request.withSubnetId(item.getSubnetId());
				ec2.deleteSubnet(request);
			} catch (Exception e) {
				System.out.println(e.getMessage());
			}
		}
		System.out.println();
	}

	void removeSubnets(AmazonEC2 ec2, String user) {
		System.out.print("Removing Subnets by tag...");
		// get id
		DescribeSubnetsRequest dRequest = new DescribeSubnetsRequest();
		Filter filter = new Filter();
		filter.withName("tag:Owner").withValues(user);
		dRequest.withFilters(filter);
		DescribeSubnetsResult dResult = ec2.describeSubnets(dRequest);
		for (Subnet item : dResult.getSubnets()) {

			try {
				// remove by id
				DeleteSubnetRequest request = new DeleteSubnetRequest();
				System.out.print(" " + item.getSubnetId() + ";");
				request.withSubnetId(item.getSubnetId());
				ec2.deleteSubnet(request);
			} catch (Exception e) {
				System.out.println(e.getMessage());
			}
		}
		System.out.println();
	}

	void removeKeyPairs(AmazonEC2 ec2, String user) {
		System.out.print("Removing KeyPairs by tag...");
		// get id
		DescribeTagsRequest dRequest = new DescribeTagsRequest();
		Filter filter = new Filter();
		filter.withName("key").withValues("Owner");
		Filter filter2 = new Filter();
		filter2.withName("value").withValues(user);
		dRequest.withFilters(filter, filter2);
		DescribeTagsResult dResult = ec2.describeTags(dRequest);
		for (TagDescription item : dResult.getTags()) {

			try {
				// remove by id
				DeleteKeyPairRequest request = new DeleteKeyPairRequest();
				System.out.print(" " + item.getResourceId() + ";");
				request.withKeyName(item.getResourceId());
				ec2.deleteKeyPair(request);
			} catch (Exception e) {
				System.out.println(e.getMessage());
			}
		}
		System.out.println();
	}

	boolean removeVpcs(AmazonEC2 ec2, ArrayList<String> vpcs) {
		System.out.println("Removing Vpcs...");
		boolean canDeactivateUser = true;
		for (String item : vpcs) {

			try {
				// remove by id
				DeleteVpcRequest request = new DeleteVpcRequest();
				request.withVpcId(item);
				ec2.deleteVpc(request);
			} catch (Exception e) {
				System.out.println(e.getMessage());
				if (!e.getMessage().contains("not exist"))
					canDeactivateUser = false;
			}
		}
		return canDeactivateUser;
	}

	void removeVpnGateways(AmazonEC2 ec2, ArrayList<String> vpcs) {
		System.out.print("Removing VpnGateways...");
		// get id
		ArrayList<String> gateways = new ArrayList<String>();
		DescribeVpnGatewaysRequest dRequest = new DescribeVpnGatewaysRequest();
		Filter filter = new Filter();
		filter.withName("attachment.vpc-id").withValues(vpcs);
		dRequest.withFilters(filter);
		DescribeVpnGatewaysResult dResult = ec2.describeVpnGateways(dRequest);
		for (VpnGateway item : dResult.getVpnGateways()) {
			for (VpcAttachment vpcAtt : item.getVpcAttachments()) {
				try {
					// remove by id
					DetachVpnGatewayRequest detRequest = new DetachVpnGatewayRequest();
					detRequest.withVpnGatewayId(item.getVpnGatewayId()).withVpcId(vpcAtt.getVpcId());
					gateways.add(item.getVpnGatewayId());
					ec2.detachVpnGateway(detRequest);
				} catch (Exception e) {
					System.out.println(e.getMessage());
				}
			}

			try {
				DeleteVpnGatewayRequest request = new DeleteVpnGatewayRequest();
				System.out.print(" " + item.getVpnGatewayId() + ";");
				request.withVpnGatewayId(item.getVpnGatewayId());
				ec2.deleteVpnGateway(request);
			} catch (Exception e) {
				System.out.println(e.getMessage());
			}
		}
		System.out.println();
		removeVpnConnections(ec2, gateways);
	}

	void removeVpnGateways(AmazonEC2 ec2, String user) {
		System.out.print("Removing VpnGateways by tag...");
		// get id
		ArrayList<String> gateways = new ArrayList<String>();
		DescribeVpnGatewaysRequest dRequest = new DescribeVpnGatewaysRequest();
		Filter filter = new Filter();
		filter.withName("tag:Owner").withValues(user);
		dRequest.withFilters(filter);
		DescribeVpnGatewaysResult dResult = ec2.describeVpnGateways(dRequest);
		for (VpnGateway item : dResult.getVpnGateways()) {
			for (VpcAttachment vpcAtt : item.getVpcAttachments()) {
				try {
					// remove by id
					DetachVpnGatewayRequest detRequest = new DetachVpnGatewayRequest();
					gateways.add(item.getVpnGatewayId());
					detRequest.withVpnGatewayId(item.getVpnGatewayId()).withVpcId(vpcAtt.getVpcId());
					ec2.detachVpnGateway(detRequest);
				} catch (Exception e) {
					System.out.println(e.getMessage());
				}
			}

			try {
				DeleteVpnGatewayRequest request = new DeleteVpnGatewayRequest();
				System.out.print(" " + item.getVpnGatewayId() + ";");
				request.withVpnGatewayId(item.getVpnGatewayId());
				ec2.deleteVpnGateway(request);
			} catch (Exception e) {
				System.out.println(e.getMessage());
			}
		}
		System.out.println();
		removeVpnConnections(ec2, gateways);
	}

	void removeEgressOnlyInternetGateways(AmazonEC2 ec2, ArrayList<String> vpcs) {
		System.out.print("Removing EgressOnlyInternetGateways...");
		// get id
		DescribeEgressOnlyInternetGatewaysResult dResult = ec2
				.describeEgressOnlyInternetGateways(new DescribeEgressOnlyInternetGatewaysRequest());
		for (EgressOnlyInternetGateway item : dResult.getEgressOnlyInternetGateways()) {
			for (InternetGatewayAttachment att : item.getAttachments()) {
				if (arrayContains(vpcs, att.getVpcId())) {
					try {
						// remove by id
						DeleteEgressOnlyInternetGatewayRequest request = new DeleteEgressOnlyInternetGatewayRequest();
						System.out.print(" " + item.getEgressOnlyInternetGatewayId() + ";");
						request.withEgressOnlyInternetGatewayId(item.getEgressOnlyInternetGatewayId());
						ec2.deleteEgressOnlyInternetGateway(request);
					} catch (Exception e) {
						System.out.println(e.getMessage());
					}
					break;
				}
			}
		}
		System.out.println();
	}

	boolean arrayContains(ArrayList<String> list, String value) {
		for (String item : list) {
			if (value.compareTo(item) == 0)
				return true;
		}
		return false;
	}

	void deleteUserData(AmazonDynamoDB db, String userTable, String resourceTable, String region, String user) {
		System.out.print("Deleting user resources...");
		// scan user resources
		HashMap<String, String> keys = new HashMap<String, String>();
		keys.put("#U", "User");
		HashMap<String, AttributeValue> values = new HashMap<String, AttributeValue>();
		values.put(":user", (new AttributeValue()).withS(user));
		values.put(":r", (new AttributeValue()).withS(region));
		ScanRequest request = new ScanRequest().withTableName(resourceTable)
				.withFilterExpression("#U = :user AND ResourceRegion = :r").withExpressionAttributeValues(values)
				.withExpressionAttributeNames(keys);
		ScanResult response = db.scan(request);
		for (Map<String, AttributeValue> item : response.getItems()) {

			// remove user resources
			try {
				DeleteItemRequest dRequest = new DeleteItemRequest();
				System.out.print(" " + item.get("ResourceId").getS() + ";");
				dRequest.withTableName(resourceTable).addKeyEntry("ResourceId", item.get("ResourceId"))
						.addKeyEntry("ResourceRegion", item.get("ResourceRegion"));
				db.deleteItem(dRequest);
			} catch (Exception e) {
				System.out.println(e.getMessage());
			}

		}
		System.out.println();
	}

	void deactivateUser(AmazonDynamoDB db, String userTable, String resourceTable, String user) {
		System.out.println("Deactivating user...");
		// set user inactive
		UpdateItemRequest uRequest = new UpdateItemRequest();
		HashMap<String, AttributeValue> uValues = new HashMap<String, AttributeValue>();
		HashMap<String, AttributeValue> uKey = new HashMap<String, AttributeValue>();
		uValues.put(":a", (new AttributeValue()).withBOOL(false));
		uKey.put("Email", (new AttributeValue()).withS(user));
		uRequest.withTableName(userTable).withExpressionAttributeValues(uValues).withUpdateExpression("SET Active = :a REMOVE RetryCount, NextRetryDate")
				.withKey(uKey);
		db.updateItem(uRequest);

		uRequest = new UpdateItemRequest();
		uRequest.withTableName(userTable).withUpdateExpression("REMOVE ProcessedRegions").withKey(uKey);
		db.updateItem(uRequest);
	}
	
	void setUserRetry(AmazonDynamoDB db, String userTable, int retryCount, String user) {
		System.out.println("Updating user retry...");
		// set user inactive
		UpdateItemRequest uRequest = new UpdateItemRequest();
		HashMap<String, AttributeValue> uValues = new HashMap<String, AttributeValue>();
		HashMap<String, AttributeValue> uKey = new HashMap<String, AttributeValue>();
		if(retryCount == 3)
			retryCount = 0;
		else
			retryCount++;
		int now = (int) System.currentTimeMillis() / 1000 + 60*60*2;
		uValues.put(":r", (new AttributeValue()).withN(Integer.toString(retryCount)));
		uValues.put(":d", (new AttributeValue()).withN(Integer.toString(now)));
		uKey.put("Email", (new AttributeValue()).withS(user));
		uRequest.withTableName(userTable).withExpressionAttributeValues(uValues).withUpdateExpression("SET RetryCount = :r, NextRetryDate = :d")
				.withKey(uKey);
		db.updateItem(uRequest);

		uRequest = new UpdateItemRequest();
		uRequest.withTableName(userTable).withUpdateExpression("REMOVE ProcessedRegions").withKey(uKey);
		db.updateItem(uRequest);
	}

	void updateProcessedRegions(AmazonDynamoDB db, String userTable, String processedRegions, String user) {
		System.out.println("Update processed regions...");
		// set user inactive
		UpdateItemRequest uRequest = new UpdateItemRequest();
		HashMap<String, AttributeValue> uValues = new HashMap<String, AttributeValue>();
		HashMap<String, AttributeValue> uKey = new HashMap<String, AttributeValue>();
		uValues.put(":r", (new AttributeValue()).withS(processedRegions));
		uKey.put("Email", (new AttributeValue()).withS(user));
		uRequest.withTableName(userTable).withExpressionAttributeValues(uValues)
				.withUpdateExpression("SET ProcessedRegions = :r").withKey(uKey);
		db.updateItem(uRequest);
	}

}

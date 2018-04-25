package com.ngworkshop.lambda;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.dynamodbv2.model.DeleteItemRequest;
import com.amazonaws.services.dynamodbv2.model.ScanRequest;
import com.amazonaws.services.dynamodbv2.model.ScanResult;
import com.amazonaws.services.dynamodbv2.model.UpdateItemRequest;
import com.amazonaws.services.dynamodbv2.model.UpdateItemResult;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder;
import com.amazonaws.services.ec2.model.*;
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
		AmazonDynamoDB client = AmazonDynamoDBClientBuilder.standard().build();

		boolean useTrail = System.getenv("USE_TRAIL") != null
				&& System.getenv("USE_TRAIL").compareToIgnoreCase("true") == 0;
		String region = System.getenv("REGION");
		String userTable = System.getenv("USER_TABLE");
		String resourceTable = System.getenv("RESOURCE_TABLE");
		ArrayList<String> users = getExpiredUsers(client, userTable, region);
		if (System.getenv("TEST_USER") != null) {
			users.add(System.getenv("TEST_USER"));
		}
		ArrayList<String> vpcs = new ArrayList<String>();
		ArrayList<String> instances = new ArrayList<String>();
		ArrayList<String> keys = new ArrayList<String>();
		System.out.println("Users: " + String.join(",", users));
		for (String user : users) {
			if (useTrail) {
				scanCloudTrail(ec2, userTable, user, vpcs, instances);
			} else {
				getInstancesByTag(ec2, user, instances, keys);
				System.out.println("Instances: " + String.join(",", instances));
				terminateInstances(ec2, instances);
				removeKeys(ec2, keys);
				getVpcsByTag(ec2, user, vpcs);
				System.out.println("Vpcs: " + String.join(",", vpcs));
				removeNatGateways(ec2, vpcs);
				removeVpcEndpoints(ec2, vpcs);
				removeVpcPeeringConnections(ec2, vpcs);
				removeRoutes(ec2, vpcs);
				removeSubnets(ec2, vpcs);
				removeInternetGateways(ec2, vpcs);
				removeEgressOnlyInternetGateways(ec2, vpcs);
				removeVpnGateways(ec2, vpcs);
				removeVpnConnections(ec2, vpcs);
				removeSecurityGroups(ec2, vpcs);
				removeNetworkAcls(ec2, vpcs);
				removeNetworkInterfaces(ec2, vpcs);
				removeVpcs(ec2, vpcs);

				removeFpgaImages(ec2, vpcs, user);
				removeImages(ec2, vpcs, user);
				removeBundleTasks(ec2, vpcs, user);
				removeDhcpOptions(ec2, vpcs, user);
				removeVolumes(ec2, vpcs, user);
				removeSnapshots(ec2, vpcs, user);
				removeAddresses(ec2, vpcs, user);
				removeLaunchTemplates(ec2, vpcs);
				removeSpotInstanceRequests(ec2, vpcs, user);

				deleteUserData(client, userTable, resourceTable, region, user);
				deactivateUser(client, userTable, resourceTable, user);

			}
		}
	}

	ArrayList<String> getExpiredUsers(AmazonDynamoDB db, String table, String region) {
		HashMap<String, AttributeValue> values = new HashMap<String, AttributeValue>();
		values.put(":end", (new AttributeValue()).withN((new Date()).getTime() + ""));
		values.put(":r", (new AttributeValue()).withS(region));
		values.put(":a", (new AttributeValue()).withBOOL(true));
		ScanRequest request = new ScanRequest().withTableName(table)
				.withFilterExpression("ExpirationDate < :end AND Active = :a AND ResourceRegion = :r")
				.withExpressionAttributeValues(values);
		ScanResult response = db.scan(request);
		ArrayList<String> result = new ArrayList<String>();
		for (Map<String, AttributeValue> item : response.getItems()) {
			result.add(item.get("Email").getS());
		}
		return result;
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
		if (System.getenv("TEST_USER") != null) {
			instances.add("i-123");
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

	void removeElastics(AmazonEC2 ec2, ArrayList<String> instances) {

	}

	void removeNatGateways(AmazonEC2 ec2, ArrayList<String> vpcs) {
		System.out.println("Removing NatGateways...");
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
				request.withNatGatewayId(item.getNatGatewayId());
				ec2.deleteNatGateway(request);
			} catch (Exception e) {
				System.out.println(e.getMessage());
			}
		}
	}

	void removeFpgaImages(AmazonEC2 ec2, ArrayList<String> vpcs, String user) {
		System.out.println("Removing FpgaImages...");
		// get id
		DescribeFpgaImagesRequest dRequest = new DescribeFpgaImagesRequest();
		Filter filter = new Filter();
		filter.withName("tag:Owner").withValues(user);
		dRequest.withFilters(filter);
		DescribeFpgaImagesResult dResult = ec2.describeFpgaImages(dRequest);
		for (FpgaImage item : dResult.getFpgaImages()) {

			try {
				// remove by id
				DeleteFpgaImageRequest request = new DeleteFpgaImageRequest();
				request.withFpgaImageId(item.getFpgaImageId());
				ec2.deleteFpgaImage(request);
			} catch (Exception e) {
				System.out.println(e.getMessage());
			}
		}
	}

	void removeAddresses(AmazonEC2 ec2, ArrayList<String> vpcs, String user) {
		System.out.println("Removing Addresses...");
		// get id
		DescribeAddressesRequest dRequest = new DescribeAddressesRequest();
		Filter filter = new Filter();
		filter.withName("tag:Owner").withValues(user);
		dRequest.withFilters(filter);
		DescribeAddressesResult dResult = ec2.describeAddresses(dRequest);
		for (Address item : dResult.getAddresses()) {

			try {
				// remove by id
				DisassociateAddressRequest request = new DisassociateAddressRequest();
				if (item.getPublicIp() != null)
					request.withPublicIp(item.getPublicIp());
				if (item.getAssociationId() != null)
					request.withAssociationId(item.getAssociationId());
				ec2.disassociateAddress(request);
			} catch (Exception e) {
				System.out.println(e.getMessage());
			}

			try {
				// remove by id
				ReleaseAddressRequest request = new ReleaseAddressRequest();
				if (item.getPublicIp() != null)
					request.withPublicIp(item.getPublicIp());
				if (item.getAllocationId() != null)
					request.withAllocationId(item.getAllocationId());
				ec2.releaseAddress(request);
			} catch (Exception e) {
				System.out.println(e.getMessage());
			}
		}
	}

	void removeSnapshots(AmazonEC2 ec2, ArrayList<String> vpcs, String user) {
		System.out.println("Removing Snapshots...");
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
				request.withSnapshotId(item.getSnapshotId());
				ec2.deleteSnapshot(request);
			} catch (Exception e) {
				System.out.println(e.getMessage());
			}
		}
	}

	void removeVolumes(AmazonEC2 ec2, ArrayList<String> vpcs, String user) {
		System.out.println("Removing Volumes...");
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
					request.withVolumeId(item.getVolumeId());
					ec2.deleteVolume(request);
				} catch (Exception e) {
					System.out.println(e.getMessage());
				}
			}
		} catch (Exception e) {
			System.out.println(e.getMessage());
		}
	}

	void removeDhcpOptions(AmazonEC2 ec2, ArrayList<String> vpcs, String user) {
		System.out.println("Removing DhcpOptions...");
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
				request.withDhcpOptionsId(item.getDhcpOptionsId());
				ec2.deleteDhcpOptions(request);
			} catch (Exception e) {
				System.out.println(e.getMessage());
			}
		}
	}

	void removeImages(AmazonEC2 ec2, ArrayList<String> vpcs, String user) {
		System.out.println("Removing Images...");
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
				request.withImageId(item.getImageId());
				ec2.deregisterImage(request);
			} catch (Exception e) {
				System.out.println(e.getMessage());
			}
		}
	}

	void removeNetworkInterfaces(AmazonEC2 ec2, ArrayList<String> vpcs) {
		System.out.println("Removing NetworkInterfaces...");
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
				request.withNetworkInterfaceId(item.getNetworkInterfaceId());
				ec2.deleteNetworkInterface(request);
			} catch (Exception e) {
				System.out.println(e.getMessage());
			}
		}
	}

	void removeLaunchTemplates(AmazonEC2 ec2, ArrayList<String> vpcs) {
		System.out.println("Removing LaunchTemplates...");
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
				request.withLaunchTemplateId(item.getLaunchTemplateId());
				ec2.deleteLaunchTemplate(request);
			} catch (Exception e) {
				System.out.println(e.getMessage());
			}
		}
	}

	void removeSpotInstanceRequests(AmazonEC2 ec2, ArrayList<String> vpcs, String user) {
		System.out.println("Removing SpotInstanceRequests...");
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
				request.withSpotInstanceRequestIds(item.getSpotInstanceRequestId());
				ec2.cancelSpotInstanceRequests(request);
			} catch (Exception e) {
				System.out.println(e.getMessage());
			}
		}
	}

	void removeBundleTasks(AmazonEC2 ec2, ArrayList<String> vpcs, String user) {
		System.out.println("Removing BundleTasks...");
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
				request.withBundleId(item.getBundleId());
				ec2.cancelBundleTask(request);
			} catch (Exception e) {
				System.out.println(e.getMessage());
			}
		}
	}

	void removeRoutes(AmazonEC2 ec2, ArrayList<String> vpcs) {
		System.out.println("Removing Routes...");
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
				request.withRouteTableId(item.getRouteTableId());
				ec2.deleteRouteTable(request);
			} catch (Exception e) {
				System.out.println(e.getMessage());
			}
		}
	}

	void removeSecurityGroups(AmazonEC2 ec2, ArrayList<String> vpcs) {
		System.out.println("Removing SecurityGroups...");
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
				request.withGroupId(item.getGroupId());
				ec2.deleteSecurityGroup(request);
			} catch (Exception e) {
				System.out.println(e.getMessage());
			}
		}
	}

	void removeVpcPeeringConnections(AmazonEC2 ec2, ArrayList<String> vpcs) {
		System.out.println("Removing VpcPeeringConnections...");
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
				request.withVpcPeeringConnectionId(item.getVpcPeeringConnectionId());
				ec2.deleteVpcPeeringConnection(request);
			} catch (Exception e) {
				System.out.println(e.getMessage());
			}
		}
	}

	void removeVpcEndpoints(AmazonEC2 ec2, ArrayList<String> vpcs) {
		System.out.println("Removing VpcEndpoints...");
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
				request.withVpcEndpointIds(item.getVpcEndpointId());
				ec2.deleteVpcEndpoints(request);
			} catch (Exception e) {
				System.out.println(e.getMessage());
			}
		}
	}

	void removeInternetGateways(AmazonEC2 ec2, ArrayList<String> vpcs) {
		System.out.println("Removing InternetGateways...");
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
					DetachVpnGatewayRequest detRequest = new DetachVpnGatewayRequest();
					detRequest.withVpnGatewayId(item.getInternetGatewayId()).withVpcId(vpcAtt.getVpcId());
					ec2.detachVpnGateway(detRequest);
				} catch (Exception e) {
					System.out.println(e.getMessage());
				}
			}
			try {
				// remove by id
				DeleteInternetGatewayRequest request = new DeleteInternetGatewayRequest();
				request.withInternetGatewayId(item.getInternetGatewayId());
				ec2.deleteInternetGateway(request);
			} catch (Exception e) {
				System.out.println(e.getMessage());
			}
		}
	}

	void removeCustomerGateways(AmazonEC2 ec2, ArrayList<String> gateways) {
		System.out.println("Removing CustomerGateways...");
		for (String item : gateways) {
			try {
				// remove by id
				DeleteCustomerGatewayRequest request = new DeleteCustomerGatewayRequest();
				request.withCustomerGatewayId(item);
				ec2.deleteCustomerGateway(request);
			} catch (Exception e) {
				System.out.println(e.getMessage());
			}
		}
	}

	void removeVpnConnections(AmazonEC2 ec2, ArrayList<String> vpcs) {
		System.out.println("Removing VpnConnections...");
		// get id
		DescribeVpnConnectionsRequest dRequest = new DescribeVpnConnectionsRequest();
		Filter filter = new Filter();
		filter.withName("vpc-id").withValues(vpcs);
		dRequest.withFilters(filter);
		DescribeVpnConnectionsResult dResult = ec2.describeVpnConnections(dRequest);
		ArrayList<String> gateways = new ArrayList<String>();
		for (VpnConnection item : dResult.getVpnConnections()) {
			gateways.add(item.getCustomerGatewayId());
			try {
				// remove by id
				DeleteVpnConnectionRequest request = new DeleteVpnConnectionRequest();
				request.withVpnConnectionId(item.getVpnConnectionId());
				ec2.deleteVpnConnection(request);
			} catch (Exception e) {
				System.out.println(e.getMessage());
			}
		}

		// call remove CustomerGateways
		removeCustomerGateways(ec2, gateways);
	}

	void removeNetworkAcls(AmazonEC2 ec2, ArrayList<String> vpcs) {
		System.out.println("Removing NetworkAcls...");
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
				request.withNetworkAclId(item.getNetworkAclId());
				ec2.deleteNetworkAcl(request);
			} catch (Exception e) {
				System.out.println(e.getMessage());
			}
		}
	}

	void removeSubnets(AmazonEC2 ec2, ArrayList<String> vpcs) {
		System.out.println("Removing Subnets...");
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
				request.withSubnetId(item.getSubnetId());
				ec2.deleteSubnet(request);
			} catch (Exception e) {
				System.out.println(e.getMessage());
			}
		}
	}

	void removeVpcs(AmazonEC2 ec2, ArrayList<String> vpcs) {
		System.out.println("Removing Vpcs...");
		for (String item : vpcs) {

			try {
				// remove by id
				DeleteVpcRequest request = new DeleteVpcRequest();
				request.withVpcId(item);
				ec2.deleteVpc(request);
			} catch (Exception e) {
				System.out.println(e.getMessage());
			}
		}
	}

	void removeVpnGateways(AmazonEC2 ec2, ArrayList<String> vpcs) {
		System.out.println("Removing VpnGateways...");
		// get id
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
					ec2.detachVpnGateway(detRequest);
				} catch (Exception e) {
					System.out.println(e.getMessage());
				}
			}

			try {
				DeleteVpnGatewayRequest request = new DeleteVpnGatewayRequest();
				request.withVpnGatewayId(item.getVpnGatewayId());
				ec2.deleteVpnGateway(request);
			} catch (Exception e) {
				System.out.println(e.getMessage());
			}
		}
	}

	void removeEgressOnlyInternetGateways(AmazonEC2 ec2, ArrayList<String> vpcs) {
		System.out.println("Removing EgressOnlyInternetGateways...");
		// get id
		DescribeEgressOnlyInternetGatewaysResult dResult = ec2
				.describeEgressOnlyInternetGateways(new DescribeEgressOnlyInternetGatewaysRequest());
		for (EgressOnlyInternetGateway item : dResult.getEgressOnlyInternetGateways()) {
			for (InternetGatewayAttachment att : item.getAttachments()) {
				if (arrayContains(vpcs, att.getVpcId())) {
					try {
						// remove by id
						DeleteEgressOnlyInternetGatewayRequest request = new DeleteEgressOnlyInternetGatewayRequest();
						request.withEgressOnlyInternetGatewayId(item.getEgressOnlyInternetGatewayId());
						ec2.deleteEgressOnlyInternetGateway(request);
					} catch (Exception e) {
						System.out.println(e.getMessage());
					}
					break;
				}
			}
		}
	}

	boolean arrayContains(ArrayList<String> list, String value) {
		for (String item : list) {
			if (value.compareTo(item) == 0)
				return true;
		}
		return false;
	}

	void deleteUserData(AmazonDynamoDB db, String userTable, String resourceTable, String region, String user) {
		System.out.println("Deleting user resources...");
		// scan user resources
		HashMap<String, AttributeValue> values = new HashMap<String, AttributeValue>();
		values.put(":user", (new AttributeValue()).withN((new Date()).getTime() + ""));
		values.put(":r", (new AttributeValue()).withS(region));
		ScanRequest request = new ScanRequest().withTableName(resourceTable).withFilterExpression("User = :r")
				.withExpressionAttributeValues(values);
		ScanResult response = db.scan(request);
		for (Map<String, AttributeValue> item : response.getItems()) {

			// remove user resources
			try {
				DeleteItemRequest dRequest = new DeleteItemRequest();
				dRequest.withTableName(resourceTable).addKeyEntry("ResourceId", item.get("ResourceId"));
				db.deleteItem(dRequest);
			} catch (Exception e) {
				System.out.println(e.getMessage());
			}

		}
	}

	void deactivateUser(AmazonDynamoDB db, String userTable, String resourceTable, String user) {
		System.out.println("Deactivating user...");
		// set user inactive
		UpdateItemRequest uRequest = new UpdateItemRequest();
		HashMap<String, AttributeValue> uValues = new HashMap<String, AttributeValue>();
		HashMap<String, AttributeValue> uKey = new HashMap<String, AttributeValue>();
		uValues.put(":a", (new AttributeValue()).withBOOL(false));
		uKey.put("Email", (new AttributeValue()).withS(user));
		uRequest.withTableName(userTable).withExpressionAttributeValues(uValues).withUpdateExpression("Active = :a")
				.withKey(uKey);
		UpdateItemResult uResult = db.updateItem(uRequest);
	}

}

from __future__ import print_function
import json
import boto3
import logging
import time
import datetime

logger = logging.getLogger()
logger.setLevel(logging.INFO)

def lambda_handler(event, context):
    logger.info('Event: ' + str(event))
    #print('Received event: ' + json.dumps(event, indent=2))

    ids = []

    try:
        region = event['region']
        detail = event['detail']
        eventname = detail['eventName']
        arn = detail['userIdentity']['arn']
        principal = detail['userIdentity']['principalId']
        userType = detail['userIdentity']['type']

        if userType == 'IAMUser':
            user = detail['userIdentity']['userName']

        else:
            user = principal.split(':')[1]


        logger.info('principalId: ' + str(principal))
        logger.info('region: ' + str(region))
        logger.info('eventName: ' + str(eventname))
        logger.info('detail: ' + str(detail))

        if not detail['responseElements']:
            logger.warning('Not responseElements found')
            if detail['errorCode']:
                logger.error('errorCode: ' + detail['errorCode'])
            if detail['errorMessage']:
                logger.error('errorMessage: ' + detail['errorMessage'])
            return False

        ec2 = boto3.resource('ec2')

        if eventname == 'CreateVolume':
            ids.append(detail['responseElements']['volumeId'])
            logger.info(ids)

        elif eventname == 'RunInstances':
            items = detail['responseElements']['instancesSet']['items']
            for item in items:
                ids.append(item['instanceId'])
            logger.info(ids)
            logger.info('number of instances: ' + str(len(ids)))

            base = ec2.instances.filter(InstanceIds=ids)

            #loop through the instances
            for instance in base:
                for vol in instance.volumes.all():
                    ids.append(vol.id)
                for eni in instance.network_interfaces:
                    ids.append(eni.id)

        elif eventname == 'CreateImage':
            ids.append(detail['responseElements']['imageId'])
            logger.info(ids)

        elif eventname == 'CreateSnapshot':
            ids.append(detail['responseElements']['snapshotId'])
            logger.info(ids)

        elif eventname == 'CreateVpc':
            ids.append(detail['responseElements']['vpcId'])
            ids.append(detail['responseElements']['dhcpOptionsId'])
            # to do Find acl, route table and sec group
            response = client.describe_network_acls(
                Filters=[
                    {
                        'Name': 'vpc-id',
                        'Values': [
                            detail['responseElements']['vpcId']
                        ]
                    },
                ]
            )
            for acl in response.NetworkAcls
                ids.append(acl.NetworkAclId)
                
            response = client.describe_security_groups(
                Filters=[
                    {
                        'Name': 'vpc-id',
                        'Values': [
                            detail['responseElements']['vpcId']
                        ]
                    },
                ]
            )
            for g in response.SecurityGroups
                ids.append(g.SecurityGroupId)
                
            response = client.describe_route_tables(
                Filters=[
                    {
                        'Name': 'vpc-id',
                        'Values': [
                            detail['responseElements']['vpcId']
                        ]
                    },
                ]
            )
            for g in response.RouteTables
                ids.append(g.RouteTableId)

            logger.info(ids)

        elif eventname == 'CreateCustomerGateway':
            ids.append(detail['responseElements']['customerGatewayId'])
            logger.info(ids)
            
        elif eventname == 'CreateNatGateway':
            ids.append(detail['responseElements']['natGatewayId'])
            logger.info(ids)
            
        elif eventname == 'CreateNetworkAcl':
            ids.append(detail['responseElements']['networkAclId'])
            logger.info(ids)
            
        elif eventname == 'CreateNetworkInterface':
            ids.append(detail['responseElements']['networkInterfaceId'])
            logger.info(ids)
            
        elif eventname == 'CreateRouteTable':
            ids.append(detail['responseElements']['routeTableId'])
            logger.info(ids)
            
        elif eventname == 'CreateSecurityGroup':
            ids.append(detail['responseElements']['securityGroupId'])
            logger.info(ids)
            
        elif eventname == 'CreateSubnet':
            ids.append(detail['responseElements']['subnetId'])
            logger.info(ids)
            
        elif eventname == 'CreateVpnGateway':
            ids.append(detail['responseElements']['vpnGatewayId'])
            logger.info(ids)
            
        elif eventname == 'CreateVpcPeeringConnection':
            ids.append(detail['responseElements']['vpcPeeringConnectionId'])
            logger.info(ids)
            
        elif eventname == 'CreateVpnConnection':
            ids.append(detail['responseElements']['vpnConnectionId'])
            logger.info(ids)
            
        else:
            logger.warning('Not supported action: '+ str(eventname))

        if ids:
            for resourceid in ids:
                print('Tagging resource ' + resourceid)
            ec2.create_tags(Resources=ids, Tags=[{'Key': 'Owner', 'Value': user}, {'Key': 'PrincipalId', 'Value': principal}])

        logger.info(' Remaining time (ms): ' + str(context.get_remaining_time_in_millis()) + '\n')
        return True
    except Exception as e:
        logger.error('Something went wrong: ' + str(e))
        return False

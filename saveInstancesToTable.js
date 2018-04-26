var AWS = require("aws-sdk");
AWS.config.update({
    //accessKeyId: process.env.ACCESS_KEY,//AKIAJT4TYSXR7OHGP2TQ
    //secretAccessKey:process.env.SECRET_KEY,//uQwyDi7IcU+ToRxPLbm2fXMBNid4XHf16CzzNQKv
    region: process.env.REGION //eu-central-1
});

var docClient = new AWS.DynamoDB.DocumentClient();
var dynamodb = new AWS.DynamoDB;
//var cloudtrail = new AWS.CloudTrail({ apiVersion: '2013-11-01' });
/// main ///
exports.saveResourcesTrail = function(event, context, callback) {
    parseEvent(event, callback);
};

function parseEvent(event, callback) {
    console.log(JSON.stringify(event));
    var region = event['region'];
    event.time = new Date();
    event.data = [];
    var detail = event['detail'];
    var eventname = detail['eventName'];
    var principal = detail['userIdentity']['principalId'];
    var userType = detail['userIdentity']['type'];
    var items = detail['responseElements']['instancesSet']['items'];
    if (userType === 'IAMUser') {
        event.user = detail['userIdentity']['userName'];
    }
    else {
        event.user = principal.split(':')[1];
    }

    console.log('principalId: ' + principal);
    console.log('region: ' + region);
    console.log('eventName: ' + eventname);
    console.log('user: ' + event.user);

    if (eventname === "RunInstances") {
        items.forEach(function(item) {
            event.data.push({ arn: item['instanceId'] });
        });
        addInstanceToTable(event, callback, "AddInstance");
    }
    else if (eventname === "StartInstances") {
        items.forEach(function(item) {
            event.data.push({ arn: item['instanceId'] });
        });
        event.instanceIsActive = true;
        addInstanceToTable(event, callback, "ChangeState");
    }
    else if (eventname === "StopInstances") {
        items.forEach(function(item) {
            event.data.push({ arn: item['instanceId'] });
        });
        event.instanceIsActive = false;
        addInstanceToTable(event, callback, "ChangeState");
    }
    else if (eventname === "TerminateInstances") {
        items.forEach(function(item) {
            event.data.push({ arn: item['instanceId'] });
        });
        removeItems(event, callback);
    }
}

function addInstanceToTable(event, callback, nextFunction) {
    event.type = "EC2 Instance";
    loadUsers(event, callback, nextFunction);
}


function log(event, callback) {
    console.log(event);
    console.log(event.data);
}

/// input /// 
// event variable must contain:
// - users (list with users to scan)
// - data (list with Resources to update)
/// output ///
// data with updated span to upload to Resource table
function loadUsers(event, callback, nextFunction) {
    var filterExpr = "";
    var attrValues = {};

    // var i = 1;
    // var length = event.users.length;
    // event.users.forEach(function(item) {
    //     if (i < length) {
    //         filterExpr += "#u = :user" + i + " OR ";
    //         attrValues[":user" + i] = item;
    //     }
    //     else {
    filterExpr += "#u = :user";
    attrValues[":user"] = event.user;
    //     }//     i++;
    // });

    var params = {
        TableName: "Users",
        ExpressionAttributeNames: {
            '#u': 'Email'
        },
        FilterExpression: filterExpr,
        ExpressionAttributeValues: attrValues
    };

    docClient.scan(params, function(err, data) {
        if (err) {
            console.error("Unable to query. Error:", JSON.stringify(err, null, 2));
            callback(err, null);
        }
        else {
            console.log("Scan user query succeeded.");
            if (nextFunction === "AddInstance" || nextFunction === "ChangeState" && event.instanceIsActive) {
                data.Items.forEach(function(item) {
                    console.log(item.Email, item.ShutdownHours);
                    event.end = updateResource(event, item.Email, item.ShutdownHours);
                });
                event.data = prepareUploadItemsAdd(event);
                uploadResources(event, callback);
            }
            else if (nextFunction === "ChangeState" && !event.instanceIsActive) {
                event.data = prepareUploadItemsUpdateState(event);
                uploadResources(event, callback);
            }
        }
    });

}

function prepareUploadItemsAdd(event) {
    var newData = [];
    event.data.forEach(function(item) {
        var newItem = {
            "User": {
                S: event.user
            },
            "ResourceId": {
                S: item.arn
            },
            // "ResourceName": {
            //     S: item.name
            // },
            "ResourceType": {
                S: event.type
            },
            "FromDate": {
                N: event.time.getTime() + ""
            },
            "ToDate": {
                N: event.end.getTime() + ""
            },
            "ToDateString": {
                S: event.end + ""
            },
            "FromDateString": {
                S: event.time + ""
            },
            "Running": {
                'BOOL': true
            },
            "ResourceRegion": {
                S: event.region
            }
        };
        newData.push(newItem);
    });
    return newData;
}

function prepareUploadItemsUpdateState(event) {
    var newData = [];
    event.data.forEach(function(item) {
        var newItem = {
            "User": {
                S: event.user
            },
            "ResourceId": {
                S: item.arn
            },
            "Running": {
                'BOOL': false
            },
            "ResourceRegion": {
                S: event.region
            },
            "ResourceType": {
                S: event.type
            }
        };
        newData.push(newItem);
    });
    return newData;
}

/// input ///
// event must contain
// - data (resources):
// -- arn ( id of resource)
// -- user ( user's mail)
// -- name ( event name)
// -- type (event type )
// -- time ( start of resource)
// -- end (end of resource)
/// output ///
// resource will be uploaded to the Resource table
function uploadResources(event, callback) {
    event.data.forEach(function(item) {
        var params = {
            Item: item,
            ReturnConsumedCapacity: "TOTAL",
            TableName: "Resources"
        };
        dynamodb.putItem(params, function(err, data) {
            if (err) console.log(err, err.stack); // an error occurred
            else console.log(data); // successful response
        });
    });
}

/// input ///
// event must contain
// - data (resources):
// -- arn ( id of resource)
// -- user ( user's mail)
// -- time ( start of resource)
/// output ///
// end time will be set 
function updateResource(event, email, span) {
    // update all resorces for specified user
    var end = new Date(event.time);
    end = new Date(end.setHours(end.getHours() + span));
    return end;
}

/// input ///
// event.data
// -- arn - to delete resourse
// event.region
/// output ///
// items will be deleted
function removeItems(event, callback) {
    event.data.forEach(function(item) {
        var params = {
            Key: {
                "ResourceId": {
                    S: item.arn
                },
                "ResourceRegion": {
                    S: event.region
                }
            },
            TableName: "Resources"
        };
        dynamodb.deleteItem(params, function(err, data) {
            if (err) console.log(err, err.stack); // an error occurred
        });
    });
}

function sleep(ms) {
    ms += new Date().getTime();
    while (new Date() < ms) {}
}



/// input /// 
// event variable must contain:
// - eventName (e.g. RunInstance)
// - fromDate - the beginning of the period
// - recourceType (e.g. EC2::VPC::Instance)
/// output ///
// data - list with arn, name, type, time, user; users - list with users

var AWS = require("aws-sdk");
AWS.config.update({
  //accessKeyId: process.env.ACCESS_KEY,//AKIAJT4TYSXR7OHGP2TQ
  //secretAccessKey:process.env.SECRET_KEY,//uQwyDi7IcU+ToRxPLbm2fXMBNid4XHf16CzzNQKv
  region: process.env.REGION//eu-central-1
});

var docClient = new AWS.DynamoDB.DocumentClient();
var dynamodb = new AWS.DynamoDB;
// Create the IAM service object
var ec2 = new AWS.EC2({apiVersion: '2016-11-15'});


exports.shutdownInstances = function(event, context, callback){
    
    event.today = new Date();
    
    var params = {
        TableName : process.env.TABLE_NAME,
        FilterExpression: "ToDate < :end AND Running = :r AND ResourceType = :type",
        ExpressionAttributeValues: {
            ":end": event.today.getTime(),
            ":r" : true,
            ":type" : "EC2"
        }
    };
    
    //search for running expired instatnces 
    docClient.scan(params, function(err, data) {
        if (err) {
            console.error("Unable to query. Error:", JSON.stringify(err, null, 2));
            callback(err, null);
        } else {
            console.log("Query succeeded. Used expiration param: "+ event.today.getTime());
            

            var count = data.Items.length;
            console.log("Records got: "+ count);
            var counter = 1;
            data.Items.forEach(function(item) {
                console.log("Stopping instance "+item.ResourceArn+" for user "+item.User+".");
                var curId = [];
                curId.push(item.ResourceArn);
                var par = {
                    InstanceIds: curId
                };
                //try to stop every instance
                ec2.stopInstances(par, function(err, data) {
                    if (err) 
                        console.log(err+" terminate"); // an error occurred
                    else {
                        var params = {
                          ExpressionAttributeNames: {
                           "#R": "Running"
                          }, 
                          ExpressionAttributeValues: {
                           ":r": {
                             'BOOL': false
                            }
                          }, 
                          Key: {
                           "ResourceArn": {
                             S: item.ResourceArn
                            }, 
                           "User": {
                             S: item.User
                            }
                          }, 
                          ReturnValues: "ALL_NEW", 
                          TableName: process.env.TABLE_NAME, 
                          UpdateExpression: "SET #R = :r"
                         };
                        // update Database - set "Running" = false
                         dynamodb.updateItem(params, function(err, data) {
                           if (err) console.log(err, err.stack); // an error occurred
                            if(counter < count)
                                counter++;
                            else{
                                callback( null, "done");
                            }
                         });
                    }
                });
                        
            });
            
        }
    });
}
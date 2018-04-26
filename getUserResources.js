var AWS = require("aws-sdk");
AWS.config.update({
  //accessKeyId: process.env.ACCESS_KEY,//AKIAJT4TYSXR7OHGP2TQ
  //secretAccessKey:process.env.SECRET_KEY,//uQwyDi7IcU+ToRxPLbm2fXMBNid4XHf16CzzNQKv
  region: process.env.REGION//eu-central-1
});

var docClient = new AWS.DynamoDB.DocumentClient();


exports.getUsersResources = function(event, context, callback){
    
    event.from = new Date(Date.parse(event.from));
    event.to = new Date(Date.parse(event.to));
    
    console.log("From: "+event.from+" int "+event.from.getTime());
    console.log("To: "+event.to+" int "+event.to.getTime());
    console.log("Today: "+ (new Date()));
    
    var params = {
        TableName : process.env.TABLE_NAME,
        FilterExpression: ":from >= FromDate AND :from <= ToDate OR :to <= ToDate AND :to <= FromDate OR :from <= FromDate AND :to >= ToDate",
        ExpressionAttributeValues: {
            ":from": event.from.getTime(),
            ":to" : event.to.getTime()
        }
    };
    
    //search for running expired instatnces 
    docClient.scan(params, function(err, data) {
        if (err) {
            console.error("Unable to query. Error:", JSON.stringify(err, null, 2));
            callback(err, null);
        } else {
            var response = getCSVHeader(",");
            var responseItem = {};
            console.log("Query succeeded.");
            data.Items.forEach(function(item) {
                responseItem.User = item.User;
                responseItem.ResourceType = item.ResourceType;
                responseItem.ResourceId = item.ResourceId;
                responseItem.ResourceRegion = item.ResourceRegion;
                responseItem.Running = item.Running;
                responseItem.ActiveFromDate = item.FromDateString;
                responseItem.ActiveToDate = item.ToDateString;
                response+=convertToCSV(responseItem, ",");
            });
            response = response.substring(0, response.length -2);
            callback(null,response);
        }
    });
}

function convertToCSV(item, delimeter){
    return item.User+delimeter+item.ResourceType+delimeter+item.ResourceId+delimeter+item.ResourceRegion+delimeter+item.Running+delimeter+item.ActiveFromDate+delimeter+item.ActiveToDate+"\r\n";
}

function getCSVHeader(delimeter){
    return "User"+delimeter+"ResourceType"+delimeter+"ResourceId"+delimeter+"Region"+delimeter+"IsRunning"+delimeter+"RunningFromDate"+delimeter+"ScheduledShutdown\r\n";
}
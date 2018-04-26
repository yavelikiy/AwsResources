'use strict';

var AWS = require('aws-sdk'),
  documentClient = new AWS.DynamoDB.DocumentClient();

var https = require('https');


//Create the STS service object
AWS.config.update({
  accessKeyId: process.env.ACCESS_KEY, //AKIAJT4TYSXR7OHGP2TQ
  secretAccessKey: process.env.SECRET_KEY, //uQwyDi7IcU+ToRxPLbm2fXMBNid4XHf16CzzNQKv
  region: process.env.REGION //eu-central-1
});
var sts = new AWS.STS({ apiVersion: '2011-06-15' });
var iam = new AWS.IAM({ apiVersion: '2010-05-08' });

function createDBUser(event, callback) {
  var params = {
    Key: {
      "Email": event.username
    },
    UpdateExpression: "set ExpirationDate = :e, ShoutdownHours = :h, Active = :a",
    ExpressionAttributeValues: {
      ":e": event.enddate.getTime(),
      ":h": event.shoutdown,
      ":a": true
    },
    TableName: process.env.TABLE_NAME
  };
  //var responseBody = {'url':event.requestURL};
  documentClient.update(params, function(err, d) {
    if (err)
      callback(err, null);
    else
      // callback(null, {
      //     "statusCode": 200,
      //     "headers": { 
      //         "Access-Control-Allow-Origin": "*" 
      //     },
      //     "body": JSON.stringify(responseBody)
      // });
      callback(null, { 'url': event.requestURL });
  });
}

function getRequestURL(token) {
  // Create URL where users can use the sign-in token to sign in to 
  // the console. This URL must be used within 15 minutes after the
  // sign-in token was issued.
  var requestParams = "?Action=login";
  requestParams += "&Issuer=not_defined";
  requestParams += "&Destination=" + encodeURIComponent("https://" + process.env.REGION + ".console.aws.amazon.com/");
  requestParams += "&SigninToken=" + token;
  return 'https://signin.aws.amazon.com/federation' + requestParams;
}

function getSigninToken(event, callback) {
  var requestParams = "?Action=getSigninToken";
  //requestParams += "&SessionDuration=43200";
  requestParams += "&SessionType=json&Session=" + encodeURIComponent(JSON.stringify(event.session));

  var requestURL = "https://signin.aws.amazon.com/federation" + requestParams;
  https.get(requestURL, (resp) => {
    let data = '';

    // A chunk of data has been recieved.
    resp.on('data', (chunk) => {
      data += chunk;
    });

    // The whole response has been received. Print out the result.
    resp.on('end', () => {
      var signinToken = JSON.parse(data);

      event.requestURL = getRequestURL(signinToken.SigninToken);
      console.log('Request URL: ', event.requestURL);

      createDBUser(event, callback);

    });

  }).on("error", (err) => {
    console.log("Error: " + err.message);
  });
}

function createTempUser(event, callback) {

  var params = {
    DurationSeconds: 3600 * 12,
    ExternalId: event.username,
    RoleArn: "arn:aws:iam::827587842911:role/federatedRole",
    RoleSessionName: event.username
  };
  sts.assumeRole(params, function(err, data) {
    if (err) {
      console.log(err, err.stack); // an error occurred
      callback(err, null);
    }
    else
      console.log(data); // successful response

    // Step 3: Format resulting temporary credentials into JSON
    event.session = {
      "sessionId": data.Credentials.AccessKeyId,
      "sessionKey": data.Credentials.SecretAccessKey,
      "sessionToken": data.Credentials.SessionToken
    }

    getSigninToken(event, callback);
  });

  // var params = {
  //   DurationSeconds: 3600*12, // modified to 12 hours
  //   Name: event.username, 
  //   Policy: event.Policy
  // };
  // sts.getFederationToken(params, function(err, data) {
  //   if (err) {
  //       console.log(err, err.stack); // an error occurred
  //       callback(err, null);
  //   }
  //   else     
  //     console.log(data);           // successful response

  //     // Step 3: Format resulting temporary credentials into JSON
  //     event.session = {
  //       "sessionId":  data.Credentials.AccessKeyId,
  //       "sessionKey": data.Credentials.SecretAccessKey,
  //       "sessionToken" : data.Credentials.SessionToken
  //     }

  //     getSigninToken(event, callback);

  // });

}

function listPolicies(event, callback) {
  var params = {
    GroupName: process.env.GROUP
  };
  event.GlobalActions = [];
  iam.listGroupPolicies(params, function(err, data) {
    if (err) console.log(err, err.stack); // an error occurred
    else {

      var params = {
        GroupName: process.env.GROUP,
        MaxItems: 100,
      };
      iam.listAttachedGroupPolicies(params, function(err, dataAtt) {
        if (err) console.log(err, err.stack); // an error occurred
        else {
          event.ManagedPolicyNames = [];
          dataAtt.AttachedPolicies.forEach(function(item) {
            event.ManagedPolicyNames.push(item.PolicyArn);
          });
          event.PolicyNames = data.PolicyNames;
          getInlinePolicy(event, callback);
        }
      });
    }
  });
}

function listPolicy(event, callback) {
  var params = {
    PolicyArn: process.env.POLICY
  };
  iam.getPolicy(params, function(err, data) {
    if (err) console.log(err, err.stack); // an error occurred
    else {
      console.log("Get Managed Policy "); // successful response

      var params = {
        PolicyArn: process.env.POLICY,
        VersionId: data.Policy.DefaultVersionId
      };
      iam.getPolicyVersion(params, function(err, dataV) {
        if (err) console.log(err, err.stack); // an error occurred
        else {
          event.Policy = JSON.stringify(JSON.parse(decodeURIComponent(dataV.PolicyVersion.Document)));
          console.log("Policy:", event.Policy);
          createTempUser(event, callback);
        }
      });
    }
  });
}

function getInlinePolicy(event, callback) {
  if (event.PolicyNames.length == 0) {
    getManagedPolicy(event, callback);
  }
  else {
    var item = event.PolicyNames[0];
    event.PolicyNames.shift();
    var params = {
      GroupName: process.env.GROUP,
      PolicyName: item
    };
    iam.getGroupPolicy(params, function(err, data) {
      if (err) console.log(err, err.stack); // an error occurred
      else {
        console.log("Get Inline Policy "); // successful response
        if (event.Policy == null) {
          event.Policy = JSON.parse(decodeURIComponent(data.PolicyDocument));
          event.Policy.Statement.forEach(function(item) {
            if (item.Effect === 'Allow')
              event.GlobalActions = addToGlobalActions(item.Action, event.GlobalActions);
          });
        }
        else {
          var policy = JSON.parse(decodeURIComponent(data.PolicyDocument));
          policy.Statement.forEach(function(item) {
            if (Array.isArray(item.Action)) {
              var i = 0,
                len = item.Action.length;
              var newAction = []
              for (; i < len; i++) {
                if (!existInGlobalActions(item.Action[i], event.GlobalActions)) {
                  newAction.push(item.Action[i]);
                }
                if (item.Effect === 'Allow')
                  event.GlobalActions = addToGlobalActions(item.Action[i], event.GlobalActions);
              }
              item.Action = newAction;
              if (item.Action.length > 0) {
                event.Policy.Statement.push(item);
              }
            }
            else {
              if (!existInGlobalActions(item.Action, event.GlobalActions)) {
                event.Policy.Statement.push(item);
              }
              if (item.Effect === 'Allow')
                event.GlobalActions = addToGlobalActions(item.Action, event.GlobalActions);
            }
          });
        }
        getInlinePolicy(event, callback);
      }
    });
  }
}

function getManagedPolicy(event, callback) {
  if (event.ManagedPolicyNames.length == 0) {
    event.Policy = JSON.stringify(event.Policy);
    console.log("Policy: ", event.Policy);
    createTempUser(event, callback);
  }
  else {
    var item = event.ManagedPolicyNames[0];
    event.ManagedPolicyNames.shift();
    var params = {
      PolicyArn: item
    };
    iam.getPolicy(params, function(err, data) {
      if (err) console.log(err, err.stack); // an error occurred
      else {
        console.log("Get Managed Policy "); // successful response

        var params = {
          PolicyArn: item,
          VersionId: data.Policy.DefaultVersionId
        };
        iam.getPolicyVersion(params, function(err, dataV) {
          if (err) console.log(err, err.stack); // an error occurred
          else {
            if (event.Policy == null) {
              event.Policy = JSON.parse(decodeURIComponent(dataV.PolicyVersion.Document));
              event.Policy.Statement.forEach(function(item) {
                if (item.Effect === 'Allow')
                  event.GlobalActions = addToGlobalActions(item.Action, event.GlobalActions);
              });
            }
            else {
              var policy = JSON.parse(decodeURIComponent(dataV.PolicyVersion.Document));
              policy.Statement.forEach(function(item) {
                if (Array.isArray(item.Action)) {
                  var i = 0,
                    len = item.Action.length;
                  var newAction = []
                  for (; i < len; i++) {
                    if (!existInGlobalActions(item.Action[i], event.GlobalActions)) {
                      newAction.push(item.Action[i]);
                    }
                    if (item.Effect === 'Allow')
                      event.GlobalActions = addToGlobalActions(item.Action[i], event.GlobalActions);
                  }
                  item.Action = newAction;
                  if (item.Action.length > 0) {
                    event.Policy.Statement.push(item);
                  }
                }
                else {
                  if (!existInGlobalActions(item.Action, event.GlobalActions)) {
                    event.Policy.Statement.push(item);
                  }
                  if (item.Effect === 'Allow')
                    event.GlobalActions = addToGlobalActions(item.Action, event.GlobalActions);
                }
              });
            }
            getManagedPolicy(event, callback);
          }
        });
      }
    });
  }
}

function existInGlobalActions(action, globalActions) {
  var exists = false;
  globalActions.forEach(function(item) {
    var match = action.substring(0, action.indexOf(":"));
    if (item === match)
      exists = true;
  });
  return exists;
}

function addToGlobalActions(action, globalActions) {
  if (Array.isArray(action)) {
    action.forEach(function(item) {
      globalActions = addToGlobalAction(item, globalActions);
    });
    return globalActions;
  }
  else
    return addToGlobalAction(action, globalActions);

}

function addToGlobalAction(action, globalActions) {
  var match = action.substring(0, action.indexOf(":"));
  //console.log('Try add to global: '+action+' and '+match);
  if (action === (match + ':*')) {
    if (globalActions.indexOf(match) < 0)
      globalActions.push(match);
  }
  return globalActions;
}

exports.writeUser = function(event, context, callback) {
  // switch off API functionality
  // if(event.ApiKey !== process.env.API_KEY){
  //   var forbidError = {status:403, errors:["ApiKey doesn't match"]};
  //   callback(JSON.stringify(forbidError), null);
  //   return;
  // }
  if (typeof event.username === 'undefined' || event.username === '' || event.username === null) {
    var error = { status: 400, errors: ["Request must have following format {'username':'user1','timespan':12, 'shoutdownHours':3}"] };
    callback(JSON.stringify(error), null);
    return;
  }
  if (typeof event.timespan === 'undefined' || event.timespan === '' || event.timespan === null) {
    event.timespan = '12';
  }
  event.enddate = new Date();
  event.timespan = parseInt(event.timespan, 10);
  event.enddate.setHours(event.enddate.getHours() + event.timespan);
  //calculate Trail delay
  event.enddate.setMinutes(event.enddate.getMinutes() + 20);
  event.shoutdown = parseInt(event.shutdownHours, 10);

  //createTempUser(event, callback);
  event.Policy = null;
  listPolicies(event, callback);
};

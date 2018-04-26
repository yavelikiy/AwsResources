'use strict';

var AWS = require('aws-sdk');

var ec2 = new AWS.EC2();
exports.handler = (event, context, callback) => {


    var params = {
        Filters: [{
                Name: 'tag-key',
                Values: [
                    'Owner'
                ]
            },
            /* more items */
        ],
        InstanceIds: [
            event.detail["instance-id"]
        ]
    };
    ec2.describeInstances(params, function(err, data) {
        console.log(JSON.stringify(data));
        if (err) console.log(err, err.stack); // an error occurred
        else if (data.Reservations.length == 0) {
            var params = {
                Resources: [
                    event.detail["instance-id"]
                ],
                Tags: [{
                    Key: "Owner",
                    Value: "INPROGRESS"
                }]
            };
            ec2.createTags(params, function(err, data) {
                if (err) console.log(err, err.stack); // an error occurred
                callback(null, null);
            });
        }
        else
            callback(null, null);
    });


};

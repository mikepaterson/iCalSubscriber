var exec = require('cordova/exec');

exports.subscribe = function(iCalUrl, success, error)
{
    exec(success, error, "iCalSubscriber", "subscribe", [iCalUrl]);
};

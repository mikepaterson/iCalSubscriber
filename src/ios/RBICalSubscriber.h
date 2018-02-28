#import <Cordova/CDVPlugin.h>

@interface RBICalSubscriber : CDVPlugin
- (void)subscribe:(CDVInvokedUrlCommand*)command;
@end
#import <Foundation/Foundation.h>
#import <Capacitor/Capacitor.h>

// Exposes HttpServerPlugin (Swift) to the Capacitor runtime, which is an
// Objective-C registry. All methods are @objc exported from Swift.
CAP_PLUGIN(HttpServerPlugin, "HttpServer",
           CAP_PLUGIN_METHOD(start, CAPPluginReturnPromise);
           CAP_PLUGIN_METHOD(stop, CAPPluginReturnPromise);
           CAP_PLUGIN_METHOD(respond, CAPPluginReturnPromise);
           CAP_PLUGIN_METHOD(addListener, CAPPluginReturnCallback);
           CAP_PLUGIN_METHOD(removeAllListeners, CAPPluginReturnPromise);
)

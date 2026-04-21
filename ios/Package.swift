// swift-tools-version:5.9
import PackageDescription

// Swift Package Manager manifest used only for local development and testing
// of the plugin sources. Consumers install the plugin via CocoaPods using
// `CantooCapacitorHttpServer.podspec` at the repository root.
let package = Package(
    name: "CantooCapacitorHttpServer",
    platforms: [.iOS(.v13)],
    products: [
        .library(
            name: "CantooCapacitorHttpServer",
            targets: ["CantooCapacitorHttpServer"]
        )
    ],
    dependencies: [
        .package(url: "https://github.com/ionic-team/capacitor-swift-pm.git", from: "7.0.0"),
        .package(url: "https://github.com/swisspol/GCDWebServer.git", from: "3.5.4")
    ],
    targets: [
        .target(
            name: "CantooCapacitorHttpServer",
            dependencies: [
                .product(name: "Capacitor", package: "capacitor-swift-pm"),
                .product(name: "Cordova", package: "capacitor-swift-pm"),
                .product(name: "GCDWebServer", package: "GCDWebServer")
            ],
            path: "Plugin"
        )
    ]
)

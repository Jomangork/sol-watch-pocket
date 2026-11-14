# Python calls these by name (sender.deliver / sender.status), so R8 must keep them.
-keep class local.solwatch.pocket.BridgeService$Sender { public *; }

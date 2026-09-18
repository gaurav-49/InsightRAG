# Halcyon ARM-X2 Collaborative Robot Arm — Product Specification

Document HR-SPEC-X2, revision C. Product management owner: Priya Raman. Status: released for manufacturing, February 2026.

## 1. Overview

The ARM-X2 is a six-axis collaborative robot arm designed for light assembly, machine tending and laboratory automation. It replaces the ARM-X1 and is designed to work safely alongside people without fencing when a risk assessment permits it.

## 2. Mechanical Specifications

- Degrees of freedom: 6 rotary joints.
- Maximum payload: 7 kilograms at full reach; 10 kilograms with the arm retracted to 600 millimetres.
- Reach: 1,050 millimetres from the base centre to the tool flange.
- Repeatability: plus or minus 0.03 millimetres (ISO 9283).
- Arm weight: 24.5 kilograms.
- Maximum tool speed: 2 metres per second; limited to 250 millimetres per second in collaborative mode.
- Mounting: floor, ceiling, wall or any angle.
- Ingress protection: IP54 for the arm, IP20 for the controller.

## 3. Electrical and Power

The controller accepts 100 to 240 volts AC, 50 or 60 hertz. Typical power consumption during a standard pick-and-place cycle is 280 watts; peak consumption is 1,100 watts. An optional battery module, the PowerPack B2, supplies 4 hours of typical operation for mobile applications and recharges from empty to 80 percent in 90 minutes.

## 4. Safety

The ARM-X2 is certified to ISO 10218-1 and meets the requirements of ISO/TS 15066 for collaborative operation. Safety functions are rated PL d, Category 3 according to ISO 13849-1. The arm has 17 configurable safety functions, including joint position limits, tool speed limits, force limits and safe zones.

Collision detection stops the arm within 20 milliseconds when contact force exceeds the configured threshold; the default threshold is 150 newtons. The emergency stop button on the teach pendant and the external emergency stop input both trigger a Category 1 stop.

## 5. Software and Connectivity

The arm is programmed with the Halcyon Studio graphical editor on the teach pendant or through the Python SDK. Supported industrial protocols are Modbus TCP, EtherNet/IP and PROFINET; ROS 2 drivers are available on the Halcyon developer portal.

Connection to Halcyon Cloud is optional and uses outbound TLS 1.3 connections only. Fleet telemetry is sent every 10 seconds when cloud connection is enabled. Firmware updates are signed and cannot be installed unless the signature validates.

## 6. Operating Environment

- Operating temperature: 0 to 45 degrees Celsius.
- Humidity: up to 90 percent, non-condensing.
- Noise: below 65 dB(A) at 1 metre.

## 7. Warranty and Service

The ARM-X2 has a standard warranty of 24 months from delivery, covering parts and labour. An extended warranty of up to 60 months can be purchased at the time of order. Recommended preventive maintenance is every 10,000 operating hours or every 2 years, whichever comes first, and consists of joint inspection, brake testing and grease replacement.

## 8. Pricing and Availability

List price for the ARM-X2 with controller and teach pendant is 34,900 dollars. The PowerPack B2 battery module is 4,200 dollars. Standard lead time is 6 weeks from order confirmation. Volume discounts apply to orders of 10 units or more.

## 9. Differences from ARM-X1

Compared with the ARM-X1, the ARM-X2 increases payload from 5 to 7 kilograms, increases reach from 850 to 1,050 millimetres, improves repeatability from 0.05 to 0.03 millimetres, and adds PROFINET support and the optional battery module. The ARM-X1 reaches end of sale on 30 June 2026 and end of support on 30 June 2031.

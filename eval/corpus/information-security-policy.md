# Halcyon Robotics — Information Security Policy

Policy owner: Chief Information Security Officer (CISO). Version 3.0, approved by the Security Council on 12 January 2026. Mandatory for all employees, contractors and temporary staff with access to Halcyon systems.

## 1. Data Classification

All information is classified into one of four levels:

- **Public** — approved for release outside Halcyon, such as marketing material and published datasheets.
- **Internal** — default level for business information; may be shared with any employee.
- **Confidential** — customer data, financial results before publication, unreleased product designs and source code. Shared only with people who need it for their role.
- **Restricted** — authentication secrets, encryption keys, personal data of employees, and security vulnerability reports. Access requires written approval from the data owner and is reviewed every 90 days.

Confidential and Restricted information must never be pasted into public generative AI tools. Approved internal AI tools are listed on the security intranet page.

## 2. Accounts and Passwords

Passwords must be at least 14 characters long. Passphrases are encouraged. Passwords must not be reused across services, and the company password manager, KeyVault, must be used to store them. Passwords are not rotated on a schedule; they must be changed immediately if compromise is suspected.

Multi-factor authentication (MFA) is required for every system that supports it. Hardware security keys are mandatory for administrators and for anyone with access to production infrastructure. SMS codes are not an accepted second factor.

Accounts are disabled within 4 hours of an employee's departure. Shared accounts are prohibited except for documented service accounts, which must have a named owner.

## 3. Devices

Company laptops use full-disk encryption and are enrolled in device management before they are issued. Devices lock automatically after 5 minutes of inactivity. Operating system security updates must be installed within 14 days of release, and critical updates within 72 hours.

Personal devices may access email and chat only through the managed mobile app. Personal devices may never store Confidential or Restricted data.

Lost or stolen devices must be reported to the IT service desk within 1 hour of discovery so they can be remotely wiped.

## 4. Access Control

Access follows the principle of least privilege. Access to production systems is granted through the Gatekeeper tool for a maximum of 8 hours at a time and is logged. Standing production access is limited to the on-call site reliability engineers.

Managers must review their team's access every quarter. Access that is not confirmed within 10 days of the quarterly review request is revoked automatically.

## 5. Software and Vendors

Only software from the approved catalogue may be installed on company devices. Requests for new software go through the IT service desk and a security review that normally takes 5 business days.

Any new vendor that will process Confidential or Restricted data must pass a vendor security assessment before a contract is signed. Vendors must hold a current SOC 2 Type II report or ISO 27001 certification, or accept an on-site audit.

## 6. Reporting Security Incidents

Suspected security incidents, including phishing emails that were clicked, lost devices, and accidental data disclosure, must be reported immediately to security@halcyon.example or through the #security-help channel. Do not attempt to investigate on your own and do not delete evidence.

Reporting a mistake promptly is never a disciplinary matter. Deliberately concealing an incident is.

## 7. Security Training

All staff complete security awareness training within 30 days of joining and annually thereafter. Engineers additionally complete secure coding training every year. Quarterly phishing simulations are run for all staff; anyone who fails two simulations in a row is enrolled in a short refresher course.

## 8. Exceptions

Exceptions to this policy must be requested through the security exception form, approved by the CISO, and are valid for at most 6 months. Every exception has a named risk owner and is recorded in the risk register.

## 9. Enforcement

Violations of this policy may result in disciplinary action up to and including termination of employment or contract, and may be reported to law enforcement where required.

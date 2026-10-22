# This PC's disks and home network for the admin page's «Где хранить» (HostSurvey.kt, ADR-042 I1).
# Only Get-* cmdlets: nothing is changed. UTF-8 out, so labels and network names in Russian come through.
$ErrorActionPreference = 'SilentlyContinue'
$ProgressPreference = 'SilentlyContinue'
[Console]::OutputEncoding = [Text.Encoding]::UTF8
$parts = @{}
Get-Partition | Where-Object DriveLetter | ForEach-Object { $parts[[string]$_.DriveLetter] = $_.DiskNumber }
$volumes = @(Get-Volume | Where-Object DriveLetter | ForEach-Object {
  [pscustomobject]@{ letter = [string]$_.DriveLetter; label = $_.FileSystemLabel; fs = $_.FileSystem; type = [string]$_.DriveType
    size = $_.Size; free = $_.SizeRemaining; disk = $parts[[string]$_.DriveLetter] } })
$disks = @(Get-PhysicalDisk | ForEach-Object {
  [pscustomobject]@{ number = [int]$_.DeviceId; name = $_.FriendlyName; media = [string]$_.MediaType; bus = [string]$_.BusType
    size = $_.Size; health = [string]$_.HealthStatus } })
$networks = @(Get-NetConnectionProfile | ForEach-Object {
  [pscustomobject]@{ name = $_.Name; adapter = $_.InterfaceAlias; category = [string]$_.NetworkCategory
    addresses = @(Get-NetIPAddress -InterfaceIndex $_.InterfaceIndex -AddressFamily IPv4 | ForEach-Object { [string]$_.IPAddress }) } })
$rules = @(Get-NetFirewallRule -DisplayName 'Benbow Server' | ForEach-Object {
  $p = $_ | Get-NetFirewallPortFilter
  [pscustomobject]@{ enabled = [string]$_.Enabled; action = [string]$_.Action; direction = [string]$_.Direction
    profile = [string]$_.Profile; ports = @($p.LocalPort | ForEach-Object { [string]$_ }); protocol = [string]$p.Protocol } })
[pscustomobject]@{ system = $env:SystemDrive.Substring(0, 1); volumes = $volumes; disks = $disks; networks = $networks; rules = $rules } |
  ConvertTo-Json -Depth 4 -Compress

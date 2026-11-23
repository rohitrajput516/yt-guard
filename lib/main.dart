import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

typedef JsonMap = Map<String, dynamic>;
typedef AppMap = Map<String, String>;
typedef AppList = List<AppMap>;

void main() {
  runApp(const YTGuardApp());
}

class YTGuardApp extends StatelessWidget {
  const YTGuardApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'YT Guard',
      debugShowCheckedModeBanner: false,
      themeMode: ThemeMode.system,
      theme: ThemeData(
        useMaterial3: true,
        colorSchemeSeed: const Color(0xFF2563EB),
        brightness: Brightness.light,
      ),
      darkTheme: ThemeData(
        useMaterial3: true,
        colorSchemeSeed: const Color(0xFF3B82F6),
        brightness: Brightness.dark,
      ),
      home: const DashboardScreen(),
    );
  }
}

class GuardBridge {
  static const MethodChannel _ch = MethodChannel('com.rohit.ytguard/control');

  static JsonMap defaultConfig() {
    return {
      'shortsBlockEnabled': false,
      'ytBlacklistEnabled': false,
      'tamperShieldEnabled': false,
      'geminiBlockEnabled': false,
      'appBlockerEnabled': false,
      'studyModeFeatures': [
        'shortsBlockEnabled',
        'appBlockerEnabled',
        'tamperShieldEnabled',
      ],
      'passwords': {
        'master': '0000',
        'shorts': '2222',
        'ytAdmin': '3333',
        'tamper': '4444',
        'appBlocker': '5555',
      },
      'blockedTitleKeywords': ['vlog', 'roast', 'prank', 'gossip', 'controversy'],
      'appCategories': <JsonMap>[],
      'individualLockedApps': <JsonMap>[],
    };
  }

  static Future<JsonMap> loadConfig() async {
    try {
      final raw = await _ch.invokeMethod<String>('loadConfig');
      if (raw == null || raw.isEmpty) {
        final def = defaultConfig();
        await saveConfig(def);
        return def;
      }
      final parsed = JsonMap.from(jsonDecode(raw));
      final def = defaultConfig();
      def.forEach((k, v) {
        parsed.putIfAbsent(k, () => v);
      });
      if (parsed['passwords'] is Map) {
        final pwds = JsonMap.from(parsed['passwords']);
        (def['passwords'] as JsonMap).forEach((k, v) {
          pwds.putIfAbsent(k, () => v);
        });
        parsed['passwords'] = pwds;
      }
      return parsed;
    } catch (_) {
      return defaultConfig();
    }
  }

  static Future<void> saveConfig(JsonMap cfg) async {
    await _ch.invokeMethod('saveConfig', {'json': jsonEncode(cfg)});
  }

  static Future<bool> isAccessibilityEnabled() async {
    try {
      return await _ch.invokeMethod<bool>('isAccessibilityEnabled') ?? false;
    } catch (_) {
      return false;
    }
  }

  static Future<void> openAccessibilitySettings() async {
    await _ch.invokeMethod('openAccessibilitySettings');
  }

  static Future<bool> isOverlayEnabled() async {
    try {
      return await _ch.invokeMethod<bool>('isOverlayEnabled') ?? false;
    } catch (_) {
      return false;
    }
  }

  static Future<void> openOverlaySettings() async {
    await _ch.invokeMethod('openOverlaySettings');
  }

  static Future<bool> isUsageAccessEnabled() async {
    try {
      return await _ch.invokeMethod<bool>('isUsageAccessEnabled') ?? false;
    } catch (_) {
      return false;
    }
  }

  static Future<void> openUsageAccessSettings() async {
    await _ch.invokeMethod('openUsageAccessSettings');
  }

  static Future<bool> isBatteryOptimizationIgnored() async {
    try {
      return await _ch.invokeMethod<bool>('isBatteryOptimizationIgnored') ?? false;
    } catch (_) {
      return false;
    }
  }

  static Future<void> requestIgnoreBatteryOptimization() async {
    await _ch.invokeMethod('requestIgnoreBatteryOptimization');
  }

  static Future<bool> isNotificationEnabled() async {
    try {
      return await _ch.invokeMethod<bool>('isNotificationEnabled') ?? true;
    } catch (_) {
      return true;
    }
  }

  static Future<void> requestNotificationPermission() async {
    await _ch.invokeMethod('requestNotificationPermission');
  }

  static Future<bool> isDeviceAdminEnabled() async {
    try {
      return await _ch.invokeMethod<bool>('isDeviceAdminEnabled') ?? false;
    } catch (_) {
      return false;
    }
  }

  static Future<void> requestDeviceAdmin() async {
    await _ch.invokeMethod('requestDeviceAdmin');
  }

  static Future<void> removeDeviceAdmin() async {
    await _ch.invokeMethod('removeDeviceAdmin');
  }

  static Future<AppList> getInstalledApps() async {
    try {
      final rawList = await _ch.invokeMethod('getInstalledApps');
      if (rawList is! List) return [];
      return rawList.map((e) => AppMap.from(e as Map)).toList();
    } catch (_) {
      return [];
    }
  }
}

class _FeatureMeta {
  final String key;
  final String shortName;
  final String title;
  final String subtitle;
  final String slotKey;
  final IconData icon;

  const _FeatureMeta({
    required this.key,
    required this.shortName,
    required this.title,
    required this.subtitle,
    required this.slotKey,
    required this.icon,
  });
}

const List<_FeatureMeta> _allFeatures = [
  _FeatureMeta(
    key: 'shortsBlockEnabled',
    shortName: 'Shorts',
    title: 'YouTube Shorts Blocker',
    subtitle: 'Instant full-screen lock when Shorts opens',
    slotKey: 'shorts',
    icon: Icons.play_disabled_rounded,
  ),
  _FeatureMeta(
    key: 'appBlockerEnabled',
    shortName: 'App Lock',
    title: 'Custom App Lock',
    subtitle: 'Locks selected apps, clones & split-screen (PhonePe exempt)',
    slotKey: 'appBlocker',
    icon: Icons.apps_rounded,
  ),
  _FeatureMeta(
    key: 'tamperShieldEnabled',
    shortName: 'Tamper Shield',
    title: 'Settings Tamper Shield',
    subtitle: 'Redirects App Info, Uninstall & Accessibility to Home screen',
    slotKey: 'tamper',
    icon: Icons.admin_panel_settings_rounded,
  ),
  _FeatureMeta(
    key: 'geminiBlockEnabled',
    shortName: 'Gemini',
    title: 'Gemini Complete Blocker',
    subtitle: 'Blocks Gemini app, shortcuts, Play Store & web (Google Search safe)',
    slotKey: 'appBlocker',
    icon: Icons.auto_awesome_outlined,
  ),
  _FeatureMeta(
    key: 'ytBlacklistEnabled',
    shortName: 'Keywords',
    title: 'YouTube Keyword Filter',
    subtitle: 'Blocks videos containing your restricted title keywords',
    slotKey: 'ytAdmin',
    icon: Icons.filter_alt_off_rounded,
  ),
];

class DashboardScreen extends StatefulWidget {
  const DashboardScreen({super.key});

  @override
  State<DashboardScreen> createState() => _DashboardScreenState();
}

class _DashboardScreenState extends State<DashboardScreen> with WidgetsBindingObserver {
  JsonMap? _cfg;
  int _tabIndex = 0;
  bool _permissionsExpanded = false;

  bool _a11yEnabled = false;
  bool _overlayEnabled = false;
  bool _usageEnabled = false;
  bool _batteryUnrestricted = false;
  bool _notifEnabled = true;
  bool _adminEnabled = false;
  AppList _installedApps = [];

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _loadInitial();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _refreshStatus();
    }
  }

  Future<void> _loadInitial() async {
    final cfg = await GuardBridge.loadConfig();
    if (mounted) {
      setState(() => _cfg = cfg);
    }
    await _refreshStatus();
  }

  Future<void> _refreshStatus() async {
    final a11y = await GuardBridge.isAccessibilityEnabled();
    final overlay = await GuardBridge.isOverlayEnabled();
    final usage = await GuardBridge.isUsageAccessEnabled();
    final battery = await GuardBridge.isBatteryOptimizationIgnored();
    final notif = await GuardBridge.isNotificationEnabled();
    final admin = await GuardBridge.isDeviceAdminEnabled();
    final apps = await GuardBridge.getInstalledApps();
    if (mounted) {
      setState(() {
        _a11yEnabled = a11y;
        _overlayEnabled = overlay;
        _usageEnabled = usage;
        _batteryUnrestricted = battery;
        _notifEnabled = notif;
        _adminEnabled = admin;
        _installedApps = apps;
      });
    }
  }

  Future<void> _save() async {
    if (_cfg == null) return;
    await GuardBridge.saveConfig(_cfg!);
    if (mounted) setState(() {});
  }

  List<String> get _studyPresetKeys {
    final raw = _cfg?['studyModeFeatures'];
    if (raw is List && raw.isNotEmpty) {
      return raw.map((e) => e.toString()).toList();
    }
    return ['shortsBlockEnabled', 'appBlockerEnabled', 'tamperShieldEnabled'];
  }

  List<_FeatureMeta> get _activeFeatures {
    if (_cfg == null) return [];
    return _allFeatures.where((f) => _cfg![f.key] == true).toList();
  }

  bool get _isStudyModeActive {
    if (_cfg == null) return false;
    final keys = _studyPresetKeys;
    if (keys.isEmpty) return false;
    return keys.every((k) => _cfg![k] == true);
  }

  bool get _allCorePermissionsGranted {
    return _a11yEnabled && _overlayEnabled && _usageEnabled && _batteryUnrestricted && _notifEnabled;
  }

  void _showSnack(String msg) {
    if (!mounted) return;
    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(SnackBar(content: Text(msg), behavior: SnackBarBehavior.floating));
  }

  Future<bool> _verifyPassword({
    required String expected,
    required String title,
    String subtitle = 'Enter password to confirm this action.',
  }) async {
    final ctrl = TextEditingController();
    bool ok = false;
    String? err;

    await showDialog(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setModalState) => AlertDialog(
          title: Text(title, style: const TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(subtitle, style: const TextStyle(fontSize: 13)),
              const SizedBox(height: 14),
              TextField(
                controller: ctrl,
                obscureText: true,
                autofocus: true,
                decoration: InputDecoration(
                  labelText: 'Password',
                  border: const OutlineInputBorder(),
                  errorText: err,
                ),
                onSubmitted: (_) {
                  if (ctrl.text.trim() == expected) {
                    ok = true;
                    Navigator.pop(ctx);
                  } else {
                    setModalState(() {
                      err = 'Wrong password. Try again.';
                      ctrl.clear();
                    });
                  }
                },
              ),
            ],
          ),
          actions: [
            TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('Cancel')),
            FilledButton(
              onPressed: () {
                if (ctrl.text.trim() == expected) {
                  ok = true;
                  Navigator.pop(ctx);
                } else {
                  setModalState(() {
                    err = 'Wrong password. Try again.';
                    ctrl.clear();
                  });
                }
              },
              child: const Text('Verify'),
            ),
          ],
        ),
      ),
    );
    return ok;
  }

  Future<bool> _verifySlotPassword(String slotKey, String title, {String? subtitle}) {
    final expected = (_cfg?['passwords']?[slotKey] ?? '0000').toString();
    return _verifyPassword(
      expected: expected,
      title: title,
      subtitle: subtitle ?? 'Enter password to confirm this change.',
    );
  }

  Future<void> _startStudyMode() async {
    if (_cfg == null) return;
    if (!_a11yEnabled || !_overlayEnabled) {
      _showSnack('Please allow Accessibility and Overlay permissions first.');
      setState(() => _permissionsExpanded = true);
      return;
    }
    for (final key in _studyPresetKeys) {
      _cfg![key] = true;
    }
    await _save();
    _showSnack('Study Mode ON • Protection active');
  }

  Future<void> _stopStudyMode() async {
    if (_cfg == null) return;
    final ok = await _verifySlotPassword(
      'master',
      'Stop Study Mode',
      subtitle: 'Enter your Master Password to turn off Study Mode shields.',
    );
    if (!ok) return;
    for (final key in _studyPresetKeys) {
      _cfg![key] = false;
    }
    await _save();
    final stillActive = _activeFeatures.isNotEmpty;
    _showSnack(
      stillActive
          ? 'Study Mode stopped • Other shields are still ON'
          : 'All shields OFF • Zero battery mode active',
    );
  }

  Future<void> _editStudyModePreset() async {
    if (_cfg == null) return;
    if (_isStudyModeActive) {
      final ok = await _verifySlotPassword(
        'master',
        'Edit Study Mode Preset',
        subtitle: 'Enter your Master Password to customize Study Mode while active.',
      );
      if (!ok) return;
    }

    final selected = Set<String>.from(_studyPresetKeys);
    if (!mounted) return;
    await showDialog(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setModalState) => AlertDialog(
          title: const Text('Study Mode Shields'),
          content: SizedBox(
            width: double.maxFinite,
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: _allFeatures.map((f) {
                return CheckboxListTile(
                  contentPadding: EdgeInsets.zero,
                  title: Text(f.title, style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w600)),
                  subtitle: Text(f.shortName, style: const TextStyle(fontSize: 12)),
                  value: selected.contains(f.key),
                  onChanged: (checked) {
                    setModalState(() {
                      if (checked == true) {
                        selected.add(f.key);
                      } else if (selected.length > 1) {
                        selected.remove(f.key);
                      }
                    });
                  },
                );
              }).toList(),
            ),
          ),
          actions: [
            TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('Cancel')),
            FilledButton(
              onPressed: () async {
                _cfg!['studyModeFeatures'] = selected.toList();
                await _save();
                if (ctx.mounted) Navigator.pop(ctx);
                _showSnack('Study Mode preset saved.');
              },
              child: const Text('Save'),
            ),
          ],
        ),
      ),
    );
  }

  Future<void> _toggleFeature(_FeatureMeta f, bool newValue) async {
    if (_cfg == null) return;
    if (newValue) {
      // Turning ON is instant with zero password friction
      _cfg![f.key] = true;
      await _save();
      _showSnack('${f.shortName} shield ON');
    } else {
      // Turning OFF strictly requires password
      final ok = await _verifySlotPassword(
        f.slotKey,
        'Turn OFF ${f.title}',
        subtitle: 'Password is required to disable an active shield.',
      );
      if (ok) {
        _cfg![f.key] = false;
        await _save();
        _showSnack(
          _activeFeatures.isEmpty
              ? '${f.shortName} OFF • Zero battery mode active'
              : '${f.shortName} shield OFF',
        );
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    if (_cfg == null) {
      return const Scaffold(body: Center(child: CircularProgressIndicator()));
    }

    final pages = [
      _buildHomeControlsTab(),
      _buildAppLockTab(),
      _buildKeywordsAndPasswordsTab(),
    ];

    return Scaffold(
      appBar: AppBar(
        title: Row(
          children: [
            Icon(Icons.shield_rounded, color: Theme.of(context).colorScheme.primary),
            const SizedBox(width: 8),
            const Text('YT Guard', style: TextStyle(fontWeight: FontWeight.bold)),
          ],
        ),
        actions: [
          Padding(
            padding: const EdgeInsets.only(right: 14),
            child: _statusBadge(),
          ),
        ],
      ),
      body: pages[_tabIndex],
      bottomNavigationBar: NavigationBar(
        selectedIndex: _tabIndex,
        onDestinationSelected: (i) => setState(() => _tabIndex = i),
        destinations: const [
          NavigationDestination(
            icon: Icon(Icons.shield_outlined),
            selectedIcon: Icon(Icons.shield_rounded),
            label: 'Focus',
          ),
          NavigationDestination(
            icon: Icon(Icons.apps_outlined),
            selectedIcon: Icon(Icons.apps_rounded),
            label: 'App Lock',
          ),
          NavigationDestination(
            icon: Icon(Icons.vpn_key_outlined),
            selectedIcon: Icon(Icons.vpn_key_rounded),
            label: 'Security',
          ),
        ],
      ),
    );
  }

  Widget _statusBadge() {
    final activeCount = _activeFeatures.length;
    final isActive = activeCount > 0;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
      decoration: BoxDecoration(
        color: isActive ? const Color(0xFF1D4ED8) : const Color(0xFF15803D),
        borderRadius: BorderRadius.circular(20),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(
            isActive ? Icons.bolt_rounded : Icons.battery_saver_rounded,
            size: 14,
            color: Colors.white,
          ),
          const SizedBox(width: 4),
          Text(
            isActive ? '$activeCount Active' : 'Idle • 0% Drain',
            style: const TextStyle(color: Colors.white, fontSize: 11, fontWeight: FontWeight.bold),
          ),
        ],
      ),
    );
  }

  // ===========================================================================
  // TAB 1: FOCUS & STUDY MODE CONTROLS
  // ===========================================================================
  Widget _buildHomeControlsTab() {
    final activeList = _activeFeatures;
    final studyActive = _isStudyModeActive;
    final presetNames = _allFeatures
        .where((f) => _studyPresetKeys.contains(f.key))
        .map((f) => f.shortName)
        .toList();

    return ListView(
      padding: const EdgeInsets.fromLTRB(16, 12, 16, 24),
      children: [
        // HERO STUDY MODE CARD
        Container(
          padding: const EdgeInsets.all(20),
          decoration: BoxDecoration(
            gradient: LinearGradient(
              colors: studyActive
                  ? [const Color(0xFF1E3A8A), const Color(0xFF2563EB)]
                  : [const Color(0xFF0F172A), const Color(0xFF1E293B)],
              begin: Alignment.topLeft,
              end: Alignment.bottomRight,
            ),
            borderRadius: BorderRadius.circular(20),
            border: Border.all(
              color: studyActive ? const Color(0xFF60A5FA) : const Color(0xFF334155),
            ),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Container(
                    padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
                    decoration: BoxDecoration(
                      color: studyActive ? const Color(0xFF3B82F6) : const Color(0xFF334155),
                      borderRadius: BorderRadius.circular(20),
                    ),
                    child: Text(
                      studyActive
                          ? 'STUDY MODE ACTIVE'
                          : (activeList.isEmpty ? 'IDLE • BATTERY SAVED' : 'CUSTOM SHIELDS ON'),
                      style: const TextStyle(
                        color: Colors.white,
                        fontSize: 11,
                        fontWeight: FontWeight.bold,
                        letterSpacing: 0.5,
                      ),
                    ),
                  ),
                  const Spacer(),
                  InkWell(
                    onTap: _editStudyModePreset,
                    borderRadius: BorderRadius.circular(8),
                    child: const Padding(
                      padding: EdgeInsets.symmetric(horizontal: 6, vertical: 4),
                      child: Row(
                        children: [
                          Icon(Icons.tune_rounded, size: 15, color: Color(0xFFCBD5E1)),
                          SizedBox(width: 4),
                          Text('Preset', style: TextStyle(color: Color(0xFFCBD5E1), fontSize: 12)),
                        ],
                      ),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 14),
              Text(
                studyActive ? 'Distractions Are Locked' : 'Ready to Study?',
                style: const TextStyle(
                  color: Colors.white,
                  fontSize: 21,
                  fontWeight: FontWeight.bold,
                ),
              ),
              const SizedBox(height: 6),
              Text(
                studyActive
                    ? 'Background monitor & status bar notification are active. Stops automatically when you turn shields OFF.'
                    : 'Tap below to enable ${presetNames.join(", ")} in one tap. Background engine stays completely asleep when OFF.',
                style: const TextStyle(color: Color(0xFFCBD5E1), fontSize: 13, height: 1.35),
              ),
              const SizedBox(height: 12),
              Wrap(
                spacing: 6,
                runSpacing: 6,
                children: _allFeatures
                    .where((f) => _studyPresetKeys.contains(f.key))
                    .map((f) {
                  final isOn = _cfg![f.key] == true;
                  return Container(
                    padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
                    decoration: BoxDecoration(
                      color: isOn ? const Color(0x33FFFFFF) : const Color(0xFF0F172A),
                      borderRadius: BorderRadius.circular(20),
                      border: Border.all(
                        color: isOn ? const Color(0xFF93C5FD) : const Color(0xFF475569),
                      ),
                    ),
                    child: Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Icon(
                          isOn ? Icons.check_circle_rounded : Icons.circle_outlined,
                          size: 13,
                          color: isOn ? const Color(0xFF93C5FD) : const Color(0xFF94A3B8),
                        ),
                        const SizedBox(width: 5),
                        Text(
                          f.shortName,
                          style: const TextStyle(color: Colors.white, fontSize: 12, fontWeight: FontWeight.w500),
                        ),
                      ],
                    ),
                  );
                }).toList(),
              ),
              const SizedBox(height: 18),
              SizedBox(
                width: double.infinity,
                child: FilledButton.icon(
                  style: FilledButton.styleFrom(
                    backgroundColor: studyActive ? Colors.white : const Color(0xFF3B82F6),
                    foregroundColor: studyActive ? const Color(0xFF0F172A) : Colors.white,
                    padding: const EdgeInsets.symmetric(vertical: 14),
                    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
                  ),
                  icon: Icon(studyActive ? Icons.lock_open_rounded : Icons.play_arrow_rounded),
                  label: Text(
                    studyActive ? 'Stop Study Mode (Password)' : 'Start Study Mode (No Password)',
                    style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 14),
                  ),
                  onPressed: studyActive ? _stopStudyMode : _startStudyMode,
                ),
              ),
            ],
          ),
        ),
        const SizedBox(height: 16),

        // COLLAPSIBLE PERMISSIONS SECTION
        _buildPermissionsSection(),
        const SizedBox(height: 18),

        // PROTECTION SHIELDS LIST
        const Text(
          'Protection Shields',
          style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold),
        ),
        const SizedBox(height: 3),
        Text(
          'Turn ON anytime with 1 tap • Password required to turn OFF',
          style: TextStyle(fontSize: 12, color: Theme.of(context).hintColor),
        ),
        const SizedBox(height: 10),
        ..._allFeatures.map(_buildFeatureCard),
      ],
    );
  }

  Widget _buildPermissionsSection() {
    final allReady = _allCorePermissionsGranted;
    final showExpanded = !allReady || _permissionsExpanded;

    return Card(
      margin: EdgeInsets.zero,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(16),
        side: BorderSide(
          color: allReady ? Colors.green : Colors.orange,
          width: 1,
        ),
      ),
      child: Column(
        children: [
          ListTile(
            onTap: () => setState(() => _permissionsExpanded = !_permissionsExpanded),
            leading: Icon(
              allReady ? Icons.verified_user_rounded : Icons.warning_amber_rounded,
              color: allReady ? Colors.green : Colors.orange,
            ),
            title: Text(
              allReady ? 'All System Permissions Ready' : 'Setup Permissions Required',
              style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 14),
            ),
            subtitle: Text(
              allReady
                  ? (_adminEnabled ? 'Uninstall Lock is also Active' : 'Tap to view or enable Uninstall Lock')
                  : 'Allow the highlighted permissions for reliable blocking',
              style: const TextStyle(fontSize: 12),
            ),
            trailing: Icon(showExpanded ? Icons.expand_less : Icons.expand_more),
          ),
          if (showExpanded)
            Padding(
              padding: const EdgeInsets.fromLTRB(12, 0, 12, 12),
              child: Column(
                children: [
                  const Divider(height: 1),
                  const SizedBox(height: 10),
                  _permTile(
                    title: 'Accessibility Service',
                    subtitle: 'Detects YouTube Shorts, Gemini & Settings',
                    granted: _a11yEnabled,
                    actionLabel: 'Allow',
                    onAction: GuardBridge.openAccessibilitySettings,
                  ),
                  _permTile(
                    title: 'Display Over Other Apps',
                    subtitle: 'Shows full-screen lock overlays instantly',
                    granted: _overlayEnabled,
                    actionLabel: 'Allow',
                    onAction: GuardBridge.openOverlaySettings,
                  ),
                  _permTile(
                    title: 'Usage Access',
                    subtitle: 'Backup tracker for games & heavy apps',
                    granted: _usageEnabled,
                    actionLabel: 'Allow',
                    onAction: GuardBridge.openUsageAccessSettings,
                  ),
                  _permTile(
                    title: 'Battery Unrestricted',
                    subtitle: 'Stops Realme OS from killing active study locks',
                    granted: _batteryUnrestricted,
                    actionLabel: 'Allow',
                    onAction: GuardBridge.requestIgnoreBatteryOptimization,
                  ),
                  _permTile(
                    title: 'Status Bar Notifications',
                    subtitle: 'Shows active shields and opens YT Guard on tap',
                    granted: _notifEnabled,
                    actionLabel: 'Allow',
                    onAction: GuardBridge.requestNotificationPermission,
                  ),
                  _permTile(
                    title: 'Safe Device Admin (Uninstall Lock)',
                    subtitle: _adminEnabled
                        ? 'Active • System uninstall button is disabled'
                        : 'Optional • Blocks app uninstall (zero wipe risk)',
                    granted: _adminEnabled,
                    actionLabel: _adminEnabled ? 'Disable' : 'Enable',
                    showActionWhenGranted: true,
                    onAction: () async {
                      if (_adminEnabled) {
                        final ok = await _verifySlotPassword(
                          'tamper',
                          'Disable Uninstall Lock',
                          subtitle: 'Enter your Tamper Password to disable Device Admin.',
                        );
                        if (ok) {
                          await GuardBridge.removeDeviceAdmin();
                          await _refreshStatus();
                          _showSnack('Uninstall Lock disabled.');
                        }
                      } else {
                        await GuardBridge.requestDeviceAdmin();
                      }
                    },
                  ),
                ],
              ),
            ),
        ],
      ),
    );
  }

  Widget _permTile({
    required String title,
    required String subtitle,
    required bool granted,
    required String actionLabel,
    required VoidCallback onAction,
    bool showActionWhenGranted = false,
  }) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: Row(
        children: [
          Icon(
            granted ? Icons.check_circle_rounded : Icons.error_outline_rounded,
            size: 20,
            color: granted ? Colors.green : Colors.orange,
          ),
          const SizedBox(width: 10),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(title, style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 13)),
                Text(subtitle, style: const TextStyle(fontSize: 11)),
              ],
            ),
          ),
          if (!granted || showActionWhenGranted)
            OutlinedButton(
              style: OutlinedButton.styleFrom(
                visualDensity: VisualDensity.compact,
                padding: const EdgeInsets.symmetric(horizontal: 12),
              ),
              onPressed: onAction,
              child: Text(actionLabel, style: const TextStyle(fontSize: 12)),
            ),
        ],
      ),
    );
  }

  Widget _buildFeatureCard(_FeatureMeta f) {
    final isOn = _cfg![f.key] == true;
    final cs = Theme.of(context).colorScheme;
    return Card(
      margin: const EdgeInsets.only(bottom: 10),
      elevation: 0,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(14),
        side: BorderSide(
          color: isOn ? cs.primary : Theme.of(context).dividerColor,
          width: isOn ? 1.4 : 1,
        ),
      ),
      child: SwitchListTile(
        contentPadding: const EdgeInsets.symmetric(horizontal: 14, vertical: 4),
        secondary: CircleAvatar(
          backgroundColor: isOn ? cs.primary : Theme.of(context).dividerColor,
          foregroundColor: isOn ? cs.onPrimary : null,
          child: Icon(f.icon, size: 20),
        ),
        title: Text(f.title, style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 14)),
        subtitle: Text(f.subtitle, style: const TextStyle(fontSize: 12)),
        value: isOn,
        onChanged: (v) => _toggleFeature(f, v),
      ),
    );
  }

  // ===========================================================================
  // TAB 2: APP LOCK & CATEGORIES
  // ===========================================================================
  Widget _buildAppLockTab() {
    final categories = (_cfg!['appCategories'] as List)
        .map((e) => JsonMap.from(e as Map))
        .toList();
    final individuals = (_cfg!['individualLockedApps'] as List)
        .map((e) => JsonMap.from(e as Map))
        .toList();
    final blockerOn = _cfg!['appBlockerEnabled'] == true;

    return ListView(
      padding: const EdgeInsets.fromLTRB(16, 12, 16, 24),
      children: [
        if (!blockerOn)
          Card(
            margin: const EdgeInsets.only(bottom: 14),
            elevation: 0,
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(14),
              side: const BorderSide(color: Colors.orange),
            ),
            child: ListTile(
              leading: const Icon(Icons.info_outline_rounded, color: Colors.orange),
              title: const Text('Custom App Lock is OFF', style: TextStyle(fontWeight: FontWeight.bold, fontSize: 13)),
              subtitle: const Text('Turn it ON to enforce locks below.', style: TextStyle(fontSize: 12)),
              trailing: FilledButton(
                onPressed: () => _toggleFeature(_allFeatures[1], true),
                child: const Text('Turn ON'),
              ),
            ),
          ),
        Row(
          children: [
            Expanded(
              child: FilledButton.icon(
                icon: const Icon(Icons.create_new_folder_outlined, size: 18),
                label: const Text('New Category'),
                onPressed: _createAppCategoryDialog,
              ),
            ),
            const SizedBox(width: 10),
            Expanded(
              child: OutlinedButton.icon(
                icon: const Icon(Icons.add_moderator_outlined, size: 18),
                label: const Text('Lock App'),
                onPressed: _lockIndividualAppDialog,
              ),
            ),
          ],
        ),
        const SizedBox(height: 18),
        const Text(
          'App Categories',
          style: TextStyle(fontWeight: FontWeight.bold, fontSize: 15),
        ),
        const SizedBox(height: 3),
        Text(
          'Each category has its own password (PhonePe is always exempt)',
          style: TextStyle(fontSize: 12, color: Theme.of(context).hintColor),
        ),
        const SizedBox(height: 10),
        if (categories.isEmpty)
          _emptyHintCard('No app categories yet. Tap "New Category" to group distracting apps.'),
        ...categories.asMap().entries.map((entry) {
          final idx = entry.key;
          final cat = entry.value;
          final pkgs = List<String>.from(cat['packages'] ?? []);
          return Card(
            margin: const EdgeInsets.only(bottom: 10),
            elevation: 0,
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(14),
              side: BorderSide(color: Theme.of(context).dividerColor),
            ),
            child: Padding(
              padding: const EdgeInsets.all(14),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      const Icon(Icons.folder_special_rounded, size: 20),
                      const SizedBox(width: 8),
                      Expanded(
                        child: Text(
                          '${cat['name']} (${pkgs.length} apps)',
                          style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 14),
                        ),
                      ),
                      TextButton(
                        onPressed: () => _assignAppsToCategory(idx),
                        child: const Text('Select Apps'),
                      ),
                      IconButton(
                        icon: const Icon(Icons.delete_outline_rounded, size: 20),
                        tooltip: 'Delete Category',
                        onPressed: () async {
                          final ok = await _verifySlotPassword(
                            'appBlocker',
                            'Delete Category',
                            subtitle: 'Enter App Blocker password to remove "${cat['name']}".',
                          );
                          if (ok) {
                            (_cfg!['appCategories'] as List).removeAt(idx);
                            await _save();
                          }
                        },
                      ),
                    ],
                  ),
                  if (pkgs.isNotEmpty) ...[
                    const SizedBox(height: 4),
                    Text(
                      pkgs.map(_appNameForPackage).join(', '),
                      style: TextStyle(fontSize: 12, color: Theme.of(context).hintColor),
                    ),
                  ],
                ],
              ),
            ),
          );
        }),
        const SizedBox(height: 18),
        const Text(
          'Individual Locked Apps',
          style: TextStyle(fontWeight: FontWeight.bold, fontSize: 15),
        ),
        const SizedBox(height: 8),
        if (individuals.isEmpty)
          _emptyHintCard('No individual locked apps yet. Tap "Lock App" to lock any app with a unique password.'),
        ...individuals.asMap().entries.map((entry) {
          final idx = entry.key;
          final app = entry.value;
          return Card(
            margin: const EdgeInsets.only(bottom: 8),
            elevation: 0,
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(12),
              side: BorderSide(color: Theme.of(context).dividerColor),
            ),
            child: ListTile(
              leading: const Icon(Icons.lock_rounded),
              title: Text(app['appName'] ?? app['packageName'] ?? '', style: const TextStyle(fontWeight: FontWeight.w600)),
              subtitle: Text(app['packageName'] ?? '', style: const TextStyle(fontSize: 11)),
              trailing: IconButton(
                icon: const Icon(Icons.delete_outline_rounded),
                tooltip: 'Remove Lock',
                onPressed: () async {
                  final expected = (app['password'] ?? '5555').toString();
                  final ok = await _verifyPassword(
                    expected: expected,
                    title: 'Unlock ${app['appName']}',
                    subtitle: 'Enter this app\'s password to remove the lock.',
                  );
                  if (ok) {
                    (_cfg!['individualLockedApps'] as List).removeAt(idx);
                    await _save();
                  }
                },
              ),
            ),
          );
        }),
      ],
    );
  }

  Widget _emptyHintCard(String text) {
    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: Theme.of(context).dividerColor),
      ),
      child: Text(text, style: TextStyle(fontSize: 13, color: Theme.of(context).hintColor)),
    );
  }

  String _appNameForPackage(String pkg) {
    for (final a in _installedApps) {
      if (a['packageName'] == pkg) return a['appName'] ?? pkg;
    }
    return pkg;
  }

  Future<void> _createAppCategoryDialog() async {
    final nameCtrl = TextEditingController();
    final pwdCtrl = TextEditingController();
    String? err;
    await showDialog(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setModalState) => AlertDialog(
          title: const Text('New App Category'),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              TextField(
                controller: nameCtrl,
                decoration: const InputDecoration(
                  labelText: 'Category Name (e.g. Social Media)',
                  border: OutlineInputBorder(),
                ),
              ),
              const SizedBox(height: 12),
              TextField(
                controller: pwdCtrl,
                obscureText: true,
                decoration: InputDecoration(
                  labelText: 'Category Password (min 4 chars)',
                  border: const OutlineInputBorder(),
                  errorText: err,
                ),
              ),
            ],
          ),
          actions: [
            TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('Cancel')),
            FilledButton(
              onPressed: () async {
                final name = nameCtrl.text.trim();
                final pwd = pwdCtrl.text.trim();
                if (name.isEmpty || pwd.length < 4) {
                  setModalState(() => err = 'Enter a name and at least 4 characters.');
                  return;
                }
                (_cfg!['appCategories'] as List).add({
                  'id': 'cat_${DateTime.now().millisecondsSinceEpoch}',
                  'name': name,
                  'password': pwd,
                  'packages': <String>[],
                });
                await _save();
                if (ctx.mounted) Navigator.pop(ctx);
              },
              child: const Text('Create'),
            ),
          ],
        ),
      ),
    );
  }

  Future<void> _assignAppsToCategory(int catIndex) async {
    final cat = JsonMap.from((_cfg!['appCategories'] as List)[catIndex] as Map);
    final original = Set<String>.from(cat['packages'] ?? []);
    final selected = Set<String>.from(original);
    String query = '';

    await showDialog(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setModalState) {
          final filtered = _installedApps.where((a) {
            final n = (a['appName'] ?? '').toLowerCase();
            return query.isEmpty || n.contains(query.toLowerCase());
          }).toList();

          return AlertDialog(
            title: Text('${cat['name']} Apps'),
            content: SizedBox(
              width: double.maxFinite,
              height: 400,
              child: Column(
                children: [
                  TextField(
                    decoration: const InputDecoration(
                      hintText: 'Search apps...',
                      prefixIcon: Icon(Icons.search),
                      border: OutlineInputBorder(),
                    ),
                    onChanged: (v) => setModalState(() => query = v),
                  ),
                  const SizedBox(height: 8),
                  Expanded(
                    child: ListView.builder(
                      itemCount: filtered.length,
                      itemBuilder: (_, i) {
                        final app = filtered[i];
                        final pkg = app['packageName']!;
                        return CheckboxListTile(
                          title: Text(app['appName'] ?? pkg),
                          subtitle: Text(pkg, style: const TextStyle(fontSize: 11)),
                          value: selected.contains(pkg),
                          onChanged: (checked) {
                            setModalState(() {
                              if (checked == true) {
                                selected.add(pkg);
                              } else {
                                selected.remove(pkg);
                              }
                            });
                          },
                        );
                      },
                    ),
                  ),
                ],
              ),
            ),
            actions: [
              TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('Cancel')),
              FilledButton(
                onPressed: () async {
                  // Removing any previously locked app requires this category's password
                  final removedAny = original.any((pkg) => !selected.contains(pkg));
                  if (removedAny) {
                    final catPwd = (cat['password'] ?? '5555').toString();
                    final ok = await _verifyPassword(
                      expected: catPwd,
                      title: 'Confirm App Removal',
                      subtitle: 'Enter "${cat['name']}" password to remove locked apps.',
                    );
                    if (!ok) return;
                  }
                  cat['packages'] = selected.toList();
                  (_cfg!['appCategories'] as List)[catIndex] = cat;
                  await _save();
                  if (ctx.mounted) Navigator.pop(ctx);
                },
                child: const Text('Save'),
              ),
            ],
          );
        },
      ),
    );
  }

  Future<void> _lockIndividualAppDialog() async {
    String query = '';
    AppMap? pickedApp;
    final pwdCtrl = TextEditingController();
    String? err;

    await showDialog(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setModalState) {
          final filtered = _installedApps.where((a) {
            final n = (a['appName'] ?? '').toLowerCase();
            return query.isEmpty || n.contains(query.toLowerCase());
          }).toList();

          return AlertDialog(
            title: const Text('Lock Single App'),
            content: SizedBox(
              width: double.maxFinite,
              height: 420,
              child: Column(
                children: [
                  TextField(
                    decoration: const InputDecoration(
                      hintText: 'Search apps...',
                      prefixIcon: Icon(Icons.search),
                      border: OutlineInputBorder(),
                    ),
                    onChanged: (v) => setModalState(() => query = v),
                  ),
                  const SizedBox(height: 8),
                  Expanded(
                    child: ListView.builder(
                      itemCount: filtered.length,
                      itemBuilder: (_, i) {
                        final app = filtered[i];
                        final isSel = pickedApp?['packageName'] == app['packageName'];
                        return ListTile(
                          selected: isSel,
                          title: Text(app['appName'] ?? ''),
                          trailing: isSel ? const Icon(Icons.check_circle_rounded) : null,
                          onTap: () => setModalState(() => pickedApp = app),
                        );
                      },
                    ),
                  ),
                  const SizedBox(height: 8),
                  TextField(
                    controller: pwdCtrl,
                    obscureText: true,
                    decoration: InputDecoration(
                      labelText: 'App Password (min 4 chars)',
                      border: const OutlineInputBorder(),
                      errorText: err,
                    ),
                  ),
                ],
              ),
            ),
            actions: [
              TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('Cancel')),
              FilledButton(
                onPressed: () async {
                  final pwd = pwdCtrl.text.trim();
                  if (pickedApp == null || pwd.length < 4) {
                    setModalState(() => err = 'Select an app and enter at least 4 characters.');
                    return;
                  }
                  final list = _cfg!['individualLockedApps'] as List;
                  list.removeWhere((e) => e['packageName'] == pickedApp!['packageName']);
                  list.add({
                    'packageName': pickedApp!['packageName'],
                    'appName': pickedApp!['appName'],
                    'password': pwd,
                  });
                  await _save();
                  if (ctx.mounted) Navigator.pop(ctx);
                },
                child: const Text('Lock App'),
              ),
            ],
          );
        },
      ),
    );
  }

  // ===========================================================================
  // TAB 3: BLOCKED KEYWORDS & SECURITY PASSWORDS
  // ===========================================================================
  Widget _buildKeywordsAndPasswordsTab() {
    final keywords = List<String>.from(_cfg!['blockedTitleKeywords'] ?? []);
    final kwEnabled = _cfg!['ytBlacklistEnabled'] == true;

    return ListView(
      padding: const EdgeInsets.fromLTRB(16, 12, 16, 24),
      children: [
        if (!kwEnabled)
          Card(
            margin: const EdgeInsets.only(bottom: 14),
            elevation: 0,
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(14),
              side: const BorderSide(color: Colors.orange),
            ),
            child: ListTile(
              leading: const Icon(Icons.filter_alt_off_rounded, color: Colors.orange),
              title: const Text('Keyword Filter is OFF', style: TextStyle(fontWeight: FontWeight.bold, fontSize: 13)),
              subtitle: const Text('Turn it ON to block videos with these words.', style: TextStyle(fontSize: 12)),
              trailing: FilledButton(
                onPressed: () => _toggleFeature(_allFeatures[4], true),
                child: const Text('Turn ON'),
              ),
            ),
          ),
        Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            const Text(
              'YouTube Blocked Keywords',
              style: TextStyle(fontWeight: FontWeight.bold, fontSize: 15),
            ),
            FilledButton.tonalIcon(
              icon: const Icon(Icons.add, size: 16),
              label: const Text('Add Word'),
              onPressed: _addBlockedKeyword,
            ),
          ],
        ),
        const SizedBox(height: 8),
        if (keywords.isEmpty)
          _emptyHintCard('No blocked keywords added yet.'),
        Wrap(
          spacing: 8,
          runSpacing: 6,
          children: keywords
              .map((w) => Chip(
                    label: Text(w),
                    onDeleted: () async {
                      final ok = await _verifySlotPassword(
                        'ytAdmin',
                        'Remove Keyword',
                        subtitle: 'Enter Keyword Password to remove "$w".',
                      );
                      if (ok) {
                        (_cfg!['blockedTitleKeywords'] as List).remove(w);
                        await _save();
                      }
                    },
                  ))
              .toList(),
        ),
        const Divider(height: 36),
        const Text(
          'Security Passwords',
          style: TextStyle(fontWeight: FontWeight.bold, fontSize: 15),
        ),
        const SizedBox(height: 3),
        Text(
          'Defaults: Master 0000 • Shorts 2222 • Keywords 3333 • Tamper 4444 • App/Gemini 5555',
          style: TextStyle(fontSize: 11, color: Theme.of(context).hintColor),
        ),
        const SizedBox(height: 10),
        _passwordTile('Master Password (Stop Study Mode)', 'master'),
        _passwordTile('Shorts Blocker Password', 'shorts'),
        _passwordTile('Keyword Filter Password', 'ytAdmin'),
        _passwordTile('Tamper Shield & Uninstall Password', 'tamper'),
        _passwordTile('App Lock & Gemini Password', 'appBlocker'),
      ],
    );
  }

  Widget _passwordTile(String title, String key) {
    return Card(
      margin: const EdgeInsets.only(bottom: 8),
      elevation: 0,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(12),
        side: BorderSide(color: Theme.of(context).dividerColor),
      ),
      child: ListTile(
        leading: const Icon(Icons.key_rounded),
        title: Text(title, style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w500)),
        trailing: const Text('Change', style: TextStyle(fontWeight: FontWeight.bold)),
        onTap: () async {
          final ok = await _verifySlotPassword(
            key,
            'Verify Current Password',
            subtitle: 'Enter your current password before changing it.',
          );
          if (!ok || !mounted) return;

          final ctrl = TextEditingController();
          String? err;
          await showDialog(
            context: context,
            builder: (ctx) => StatefulBuilder(
              builder: (ctx, setModalState) => AlertDialog(
                title: Text('Change $title'),
                content: TextField(
                  controller: ctrl,
                  obscureText: true,
                  autofocus: true,
                  decoration: InputDecoration(
                    labelText: 'New Password (min 4 chars)',
                    border: const OutlineInputBorder(),
                    errorText: err,
                  ),
                ),
                actions: [
                  TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('Cancel')),
                  FilledButton(
                    onPressed: () async {
                      final np = ctrl.text.trim();
                      if (np.length < 4) {
                        setModalState(() => err = 'Password must be at least 4 characters.');
                        return;
                      }
                      (_cfg!['passwords'] as Map)[key] = np;
                      await _save();
                      if (ctx.mounted) Navigator.pop(ctx);
                      _showSnack('Password updated.');
                    },
                    child: const Text('Save'),
                  ),
                ],
              ),
            ),
          );
        },
      ),
    );
  }

  Future<void> _addBlockedKeyword() async {
    final ctrl = TextEditingController();
    await showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Add Blocked Keyword'),
        content: TextField(
          controller: ctrl,
          autofocus: true,
          decoration: const InputDecoration(
            labelText: 'Keyword (e.g. roast, prank)',
            border: OutlineInputBorder(),
          ),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('Cancel')),
          FilledButton(
            onPressed: () async {
              final w = ctrl.text.trim().toLowerCase();
              if (w.isNotEmpty) {
                final list = _cfg!['blockedTitleKeywords'] as List;
                if (!list.contains(w)) {
                  list.add(w);
                  await _save();
                }
                if (ctx.mounted) Navigator.pop(ctx);
              }
            },
            child: const Text('Add'),
          ),
        ],
      ),
    );
  }
}

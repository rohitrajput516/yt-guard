import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

typedef JsonMap = Map<String,dynamic>;
typedef AppMap = Map<String,String>;
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
      home: const MasterGateScreen(),
    );
  }
}

class GuardBridge {
  static const MethodChannel _ch = MethodChannel('com.rohit.ytguard/control');

  static JsonMap defaultConfig() {
    return {
      'shortsBlockEnabled': false,
      'ytWhitelistEnabled': false,
      'ytBlacklistEnabled': false,
      'tamperShieldEnabled': false,
      'appBlockerEnabled': false,
      'activeGroupId': 'study',
      'passwords': {
        'master': '0000',
        'shorts': '2222',
        'ytAdmin': '3333',
        'tamper': '4444',
        'appBlocker': '5555',
      },
      'blockedTitleKeywords': ['vlog', 'roast', 'prank', 'gossip', 'controversy'],
      'groups': [
        {
          'id': 'study',
          'name': 'B.Sc & Defence Study',
          'channels': ['Physics Wallah', 'Defence Wallah', 'Maths Jugad Se'],
        },
        {
          'id': 'coding',
          'name': 'Coding & Dev',
          'channels': ['CodeWithHarry', 'Hitesh Choudhary', 'Fireship'],
        },
      ],
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

class MasterGateScreen extends StatefulWidget {
  const MasterGateScreen({super.key});

  @override
  State<MasterGateScreen> createState() => _MasterGateScreenState();
}

class _MasterGateScreenState extends State<MasterGateScreen> {
  final TextEditingController _pwdCtrl = TextEditingController();
  JsonMap? _config;
  String? _error;

  @override
  void initState() {
    super.initState();
    _init();
  }

  Future<void> _init() async {
    final cfg = await GuardBridge.loadConfig();
    if (mounted) setState(() => _config = cfg);
  }

  void _unlock() {
    if (_config == null) return;
    final expected = (_config!['passwords']?['master'] ?? '0000').toString();
    if (_pwdCtrl.text.trim() == expected) {
      Navigator.of(context).pushReplacement(
        MaterialPageRoute(builder: (_) => DashboardScreen(initialConfig: _config!)),
      );
    } else {
      setState(() {
        _error = 'Galat Master Password! (Default: 0000)';
        _pwdCtrl.clear();
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: Center(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(24),
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 380),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                const Icon(Icons.shield_outlined, size: 64),
                const SizedBox(height: 16),
                const Text(
                  'YT Guard Control Panel',
                  style: TextStyle(fontSize: 22, fontWeight: FontWeight.bold),
                ),
                const SizedBox(height: 8),
                const Text(
                  'Settings access karne ke liye Master Password dalein',
                  textAlign: TextAlign.center,
                ),
                const SizedBox(height: 24),
                TextField(
                  controller: _pwdCtrl,
                  obscureText: true,
                  decoration: InputDecoration(
                    labelText: 'Master Password',
                    border: const OutlineInputBorder(),
                    errorText: _error,
                  ),
                  onSubmitted: (_) => _unlock(),
                ),
                const SizedBox(height: 16),
                SizedBox(
                  width: double.infinity,
                  child: FilledButton(
                    onPressed: _config == null ? null : _unlock,
                    child: const Padding(
                      padding: EdgeInsets.symmetric(vertical: 14),
                      child: Text('Unlock Dashboard'),
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class DashboardScreen extends StatefulWidget {
  final JsonMap initialConfig;
  const DashboardScreen({super.key, required this.initialConfig});

  @override
  State<DashboardScreen> createState() => _DashboardScreenState();
}

class _DashboardScreenState extends State<DashboardScreen> with WidgetsBindingObserver {
  late JsonMap _cfg;
  int _tabIndex = 0;
  bool _a11yEnabled = false;
  AppList _installedApps = [];

  @override
  void initState() {
    super.initState();
    _cfg = widget.initialConfig;
    WidgetsBinding.instance.addObserver(this);
    _refreshStatus();
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

  Future<void> _refreshStatus() async {
    final enabled = await GuardBridge.isAccessibilityEnabled();
    final apps = await GuardBridge.getInstalledApps();
    if (mounted) {
      setState(() {
        _a11yEnabled = enabled;
        _installedApps = apps;
      });
    }
  }

  Future<void> _save() async {
    await GuardBridge.saveConfig(_cfg);
    if (mounted) setState(() {});
  }

  Future<bool> _verifySlotPassword(String slotKey, String title) async {
    final expected = (_cfg['passwords']?[slotKey] ?? '0000').toString();
    final ctrl = TextEditingController();
    bool ok = false;

    await showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(title),
        content: TextField(
          controller: ctrl,
          obscureText: true,
          autofocus: true,
          decoration: const InputDecoration(
            labelText: 'Password dalein',
            border: OutlineInputBorder(),
          ),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('Cancel')),
          FilledButton(
            onPressed: () {
              if (ctrl.text.trim() == expected) {
                ok = true;
                Navigator.pop(ctx);
              } else {
                ScaffoldMessenger.of(context).showSnackBar(
                  const SnackBar(content: Text('Galat password!')),
                );
              }
            },
            child: const Text('Verify'),
          ),
        ],
      ),
    );
    return ok;
  }

  Future<void> _toggleFeatureWithPassword({
    required String configKey,
    required String slotKey,
    required String featureName,
    required bool newValue,
  }) async {
    final actionText = newValue ? 'ON' : 'OFF';
    final verified = await _verifySlotPassword(
      slotKey,
      '$featureName $actionText karein',
    );
    if (verified) {
      _cfg[configKey] = newValue;
      await _save();
    }
  }

  @override
  Widget build(BuildContext context) {
    final pages = [
      _buildControlsTab(),
      _buildAppBlockerTab(),
      _buildYoutubeAndPasswordsTab(),
    ];

    return Scaffold(
      appBar: AppBar(
        title: const Text('YT Guard'),
        actions: [
          IconButton(
            tooltip: 'Lock Dashboard',
            icon: const Icon(Icons.lock_outline),
            onPressed: () {
              Navigator.of(context).pushReplacement(
                MaterialPageRoute(builder: (_) => const MasterGateScreen()),
              );
            },
          ),
        ],
      ),
      body: pages[_tabIndex],
      bottomNavigationBar: NavigationBar(
        selectedIndex: _tabIndex,
        onDestinationSelected: (i) => setState(() => _tabIndex = i),
        destinations: const [
          NavigationDestination(icon: Icon(Icons.tune), label: 'Controls'),
          NavigationDestination(icon: Icon(Icons.apps_outlined), label: 'App Lock'),
          NavigationDestination(icon: Icon(Icons.vpn_key_outlined), label: 'YT & Passwords'),
        ],
      ),
    );
  }

  Widget _buildControlsTab() {
    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        Container(
          padding: const EdgeInsets.all(16),
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(14),
            border: Border.all(
              color: _a11yEnabled ? Colors.green : Colors.orange,
            ),
          ),
          child: Row(
            children: [
              Icon(
                _a11yEnabled ? Icons.check_circle : Icons.warning_amber_rounded,
                color: _a11yEnabled ? Colors.green : Colors.orange,
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      _a11yEnabled
                          ? 'Accessibility Service Active'
                          : 'Accessibility Permission Chahiye',
                      style: const TextStyle(fontWeight: FontWeight.bold),
                    ),
                    const Text(
                      'Permission dene par kuch bhi apne-aap ON nahi hota. Niche se password daal kar ON karein.',
                      style: TextStyle(fontSize: 12),
                    ),
                  ],
                ),
              ),
              if (!_a11yEnabled)
                FilledButton(
                  onPressed: GuardBridge.openAccessibilitySettings,
                  child: const Text('Enable'),
                ),
            ],
          ),
        ),
        const SizedBox(height: 16),
        _featureSwitchTile(
          title: 'YouTube Shorts Blocker',
          subtitle: 'Shorts khulne par full-screen pop-up dikhayega (No auto-back)',
          value: _cfg['shortsBlockEnabled'] == true,
          onChanged: (v) => _toggleFeatureWithPassword(
            configKey: 'shortsBlockEnabled',
            slotKey: 'shorts',
            featureName: 'Shorts Blocker',
            newValue: v,
          ),
        ),
        _featureSwitchTile(
          title: 'YouTube Category / Channel Filter',
          subtitle: 'Sirf active category ke channels chalenge, baaki par pop-up aayega',
          value: _cfg['ytWhitelistEnabled'] == true,
          onChanged: (v) => _toggleFeatureWithPassword(
            configKey: 'ytWhitelistEnabled',
            slotKey: 'ytAdmin',
            featureName: 'Category Filter',
            newValue: v,
          ),
        ),
        _featureSwitchTile(
          title: 'YouTube Keyword Blacklist',
          subtitle: 'Blocked shabd wali video chalne par pop-up aayega',
          value: _cfg['ytBlacklistEnabled'] == true,
          onChanged: (v) => _toggleFeatureWithPassword(
            configKey: 'ytBlacklistEnabled',
            slotKey: 'ytAdmin',
            featureName: 'Keyword Blacklist',
            newValue: v,
          ),
        ),
        _featureSwitchTile(
          title: 'Settings Tamper Shield',
          subtitle: 'Uninstall, Clear Data aur Accessibility page par pop-up lock',
          value: _cfg['tamperShieldEnabled'] == true,
          onChanged: (v) => _toggleFeatureWithPassword(
            configKey: 'tamperShieldEnabled',
            slotKey: 'tamper',
            featureName: 'Settings Tamper Shield',
            newValue: v,
          ),
        ),
        _featureSwitchTile(
          title: 'Custom App & Category Blocker',
          subtitle: 'Select kiye gaye apps/categories par alag password lock lagayega',
          value: _cfg['appBlockerEnabled'] == true,
          onChanged: (v) => _toggleFeatureWithPassword(
            configKey: 'appBlockerEnabled',
            slotKey: 'appBlocker',
            featureName: 'Custom App Blocker',
            newValue: v,
          ),
        ),
      ],
    );
  }

  Widget _featureSwitchTile({
    required String title,
    required String subtitle,
    required bool value,
    required ValueChanged<bool> onChanged,
  }) {
    return Container(
      margin: const EdgeInsets.only(bottom: 10),
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: Theme.of(context).dividerColor),
      ),
      child: SwitchListTile(
        title: Text(title, style: const TextStyle(fontWeight: FontWeight.w600)),
        subtitle: Text(subtitle, style: const TextStyle(fontSize: 12)),
        value: value,
        onChanged: onChanged,
      ),
    );
  }

  Widget _buildAppBlockerTab() {
    final categories = (_cfg['appCategories'] as List)
        .map((e) => JsonMap.from(e as Map))
        .toList();
    final individuals = (_cfg['individualLockedApps'] as List)
        .map((e) => JsonMap.from(e as Map))
        .toList();

    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        Row(
          children: [
            Expanded(
              child: FilledButton.icon(
                icon: const Icon(Icons.create_new_folder_outlined),
                label: const Text('Nayi Category'),
                onPressed: _createAppCategoryDialog,
              ),
            ),
            const SizedBox(width: 10),
            Expanded(
              child: OutlinedButton.icon(
                icon: const Icon(Icons.add_moderator_outlined),
                label: const Text('Lock Single App'),
                onPressed: _lockIndividualAppDialog,
              ),
            ),
          ],
        ),
        const SizedBox(height: 20),
        const Text(
          'App Categories (Har Category Ka Alag Password)',
          style: TextStyle(fontWeight: FontWeight.bold, fontSize: 15),
        ),
        const SizedBox(height: 8),
        if (categories.isEmpty)
          const Padding(
            padding: EdgeInsets.symmetric(vertical: 8),
            child: Text('Abhi koi app category nahi bani hai.'),
          ),
        ...categories.asMap().entries.map((entry) {
          final idx = entry.key;
          final cat = entry.value;
          final pkgs = List<String>.from(cat['packages'] ?? []);
          return Container(
            margin: const EdgeInsets.only(bottom: 10),
            padding: const EdgeInsets.all(12),
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(12),
              border: Border.all(color: Theme.of(context).dividerColor),
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Expanded(
                      child: Text(
                        '${cat['name']} (${pkgs.length} apps)',
                        style: const TextStyle(fontWeight: FontWeight.bold),
                      ),
                    ),
                    TextButton(
                      onPressed: () => _assignAppsToCategory(idx),
                      child: const Text('Apps Chunein'),
                    ),
                    IconButton(
                      icon: const Icon(Icons.delete_outline, size: 20),
                      onPressed: () async {
                        if (await _verifySlotPassword('appBlocker', 'Category Hatayein')) {
                          (_cfg['appCategories'] as List).removeAt(idx);
                          await _save();
                        }
                      },
                    ),
                  ],
                ),
                if (pkgs.isNotEmpty)
                  Text(
                    pkgs.map(_appNameForPackage).join(', '),
                    style: const TextStyle(fontSize: 12),
                  ),
              ],
            ),
          );
        }),
        const SizedBox(height: 20),
        const Text(
          'Individual Locked Apps (Har App Ka Alag Password)',
          style: TextStyle(fontWeight: FontWeight.bold, fontSize: 15),
        ),
        const SizedBox(height: 8),
        if (individuals.isEmpty)
          const Padding(
            padding: EdgeInsets.symmetric(vertical: 8),
            child: Text('Abhi koi individual app lock nahi hai.'),
          ),
        ...individuals.asMap().entries.map((entry) {
          final idx = entry.key;
          final app = entry.value;
          return ListTile(
            contentPadding: EdgeInsets.zero,
            leading: const Icon(Icons.lock),
            title: Text(app['appName'] ?? app['packageName'] ?? ''),
            subtitle: Text(app['packageName'] ?? ''),
            trailing: IconButton(
              icon: const Icon(Icons.delete_outline),
              onPressed: () async {
                final expected = (app['password'] ?? '5555').toString();
                final ctrl = TextEditingController();
                await showDialog(
                  context: context,
                  builder: (ctx) => AlertDialog(
                    title: Text('${app['appName']} Lock Hatayein'),
                    content: TextField(
                      controller: ctrl,
                      obscureText: true,
                      decoration: const InputDecoration(
                        labelText: 'Is app ka password dalein',
                        border: OutlineInputBorder(),
                      ),
                    ),
                    actions: [
                      TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('Cancel')),
                      FilledButton(
                        onPressed: () async {
                          if (ctrl.text.trim() == expected) {
                            (_cfg['individualLockedApps'] as List).removeAt(idx);
                            await _save();
                            if (ctx.mounted) Navigator.pop(ctx);
                          }
                        },
                        child: const Text('Remove'),
                      ),
                    ],
                  ),
                );
              },
            ),
          );
        }),
      ],
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
    await showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Nayi App Category Banayein'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
              controller: nameCtrl,
              decoration: const InputDecoration(labelText: 'Category Name (e.g. Social Media)'),
            ),
            const SizedBox(height: 12),
            TextField(
              controller: pwdCtrl,
              obscureText: true,
              decoration: const InputDecoration(labelText: 'Is Category Ka Alag Password'),
            ),
          ],
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('Cancel')),
          FilledButton(
            onPressed: () async {
              final name = nameCtrl.text.trim();
              final pwd = pwdCtrl.text.trim();
              if (name.isNotEmpty && pwd.length >= 4) {
                (_cfg['appCategories'] as List).add({
                  'id': 'cat_${DateTime.now().millisecondsSinceEpoch}',
                  'name': name,
                  'password': pwd,
                  'packages': <String>[],
                });
                await _save();
                if (ctx.mounted) Navigator.pop(ctx);
              }
            },
            child: const Text('Save'),
          ),
        ],
      ),
    );
  }

  Future<void> _assignAppsToCategory(int catIndex) async {
    final cat = JsonMap.from((_cfg['appCategories'] as List)[catIndex] as Map);
    final selected = Set<String>.from(cat['packages'] ?? []);
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
                      hintText: 'App search karein...',
                      prefixIcon: Icon(Icons.search),
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
                  cat['packages'] = selected.toList();
                  (_cfg['appCategories'] as List)[catIndex] = cat;
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

    await showDialog(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setModalState) {
          final filtered = _installedApps.where((a) {
            final n = (a['appName'] ?? '').toLowerCase();
            return query.isEmpty || n.contains(query.toLowerCase());
          }).toList();

          return AlertDialog(
            title: const Text('Single App Lock Karein'),
            content: SizedBox(
              width: double.maxFinite,
              height: 420,
              child: Column(
                children: [
                  TextField(
                    decoration: const InputDecoration(
                      hintText: 'App search karein...',
                      prefixIcon: Icon(Icons.search),
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
                          trailing: isSel ? const Icon(Icons.check_circle) : null,
                          onTap: () => setModalState(() => pickedApp = app),
                        );
                      },
                    ),
                  ),
                  const SizedBox(height: 8),
                  TextField(
                    controller: pwdCtrl,
                    obscureText: true,
                    decoration: const InputDecoration(
                      labelText: 'Is App Ka Alag Password (min 4)',
                      border: OutlineInputBorder(),
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
                  if (pickedApp != null && pwd.length >= 4) {
                    final list = _cfg['individualLockedApps'] as List;
                    list.removeWhere((e) => e['packageName'] == pickedApp!['packageName']);
                    list.add({
                      'packageName': pickedApp!['packageName'],
                      'appName': pickedApp!['appName'],
                      'password': pwd,
                    });
                    await _save();
                    if (ctx.mounted) Navigator.pop(ctx);
                  }
                },
                child: const Text('Lock App'),
              ),
            ],
          );
        },
      ),
    );
  }

  Widget _buildYoutubeAndPasswordsTab() {
    final groups = (_cfg['groups'] as List)
        .map((e) => JsonMap.from(e as Map))
        .toList();
    final activeId = (_cfg['activeGroupId'] ?? 'study').toString();
    final keywords = List<String>.from(_cfg['blockedTitleKeywords'] ?? []);

    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        const Text(
          'YouTube Active Category (Whitelist)',
          style: TextStyle(fontWeight: FontWeight.bold, fontSize: 15),
        ),
        const SizedBox(height: 8),
        ...groups.asMap().entries.map((entry) {
          final idx = entry.key;
          final g = entry.value;
          final isSelected = g['id'] == activeId;
          final chs = List<String>.from(g['channels'] ?? []);
          return Container(
            margin: const EdgeInsets.only(bottom: 10),
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(12),
              border: Border.all(
                color: isSelected
                    ? Theme.of(context).colorScheme.primary
                    : Theme.of(context).dividerColor,
              ),
            ),
            child: ListTile(
              onTap: () async {
                if (!isSelected && await _verifySlotPassword('ytAdmin', 'Category Badlein')) {
                  _cfg['activeGroupId'] = g['id'];
                  await _save();
                }
              },
              title: Text(g['name'] ?? '', style: const TextStyle(fontWeight: FontWeight.w600)),
              subtitle: Text('Channels: ${chs.join(', ')}', style: const TextStyle(fontSize: 12)),
              leading: Icon(
                isSelected ? Icons.radio_button_checked : Icons.radio_button_off,
                color: isSelected ? Theme.of(context).colorScheme.primary : null,
              ),
              trailing: IconButton(
                icon: const Icon(Icons.add),
                onPressed: () => _addChannelToGroup(idx),
              ),
            ),
          );
        }),
        const SizedBox(height: 12),
        Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            const Text(
              'Blocked Title Keywords',
              style: TextStyle(fontWeight: FontWeight.bold, fontSize: 15),
            ),
            TextButton(onPressed: _addBlockedKeyword, child: const Text('+ Add Word')),
          ],
        ),
        Wrap(
          spacing: 8,
          children: keywords
              .map((w) => Chip(
                    label: Text(w),
                    onDeleted: () async {
                      if (await _verifySlotPassword('ytAdmin', 'Keyword Hatayein')) {
                        (_cfg['blockedTitleKeywords'] as List).remove(w);
                        await _save();
                      }
                    },
                  ))
              .toList(),
        ),
        const Divider(height: 32),
        const Text(
          'System Passwords Badlein',
          style: TextStyle(fontWeight: FontWeight.bold, fontSize: 15),
        ),
        const SizedBox(height: 8),
        _passwordTile('Master App Access Password', 'master'),
        _passwordTile('Shorts Blocker Password', 'shorts'),
        _passwordTile('YouTube Category / Admin Password', 'ytAdmin'),
        _passwordTile('Settings Tamper Shield Password', 'tamper'),
        _passwordTile('App Blocker Toggle Password', 'appBlocker'),
      ],
    );
  }

  Widget _passwordTile(String title, String key) {
    return ListTile(
      contentPadding: EdgeInsets.zero,
      leading: const Icon(Icons.key),
      title: Text(title),
      trailing: const Text('Change'),
      onTap: () async {
        if (!await _verifySlotPassword(key, 'Purana Password Dalein')) return;
        final ctrl = TextEditingController();
        if (!mounted) return;
        await showDialog(
          context: context,
          builder: (ctx) => AlertDialog(
            title: Text('Naya $title'),
            content: TextField(
              controller: ctrl,
              obscureText: true,
              decoration: const InputDecoration(
                labelText: 'Naya password (min 4 chars)',
                border: OutlineInputBorder(),
              ),
            ),
            actions: [
              TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('Cancel')),
              FilledButton(
                onPressed: () async {
                  final np = ctrl.text.trim();
                  if (np.length >= 4) {
                    (_cfg['passwords'] as Map)[key] = np;
                    await _save();
                    if (ctx.mounted) Navigator.pop(ctx);
                  }
                },
                child: const Text('Save'),
              ),
            ],
          ),
        );
      },
    );
  }

  Future<void> _addChannelToGroup(int groupIdx) async {
    if (!await _verifySlotPassword('ytAdmin', 'Channel Add Karein')) return;
    final ctrl = TextEditingController();
    if (!mounted) return;
    await showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Whitelist Channel Add Karein'),
        content: TextField(
          controller: ctrl,
          decoration: const InputDecoration(labelText: 'Channel ka naam'),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('Cancel')),
          FilledButton(
            onPressed: () async {
              final ch = ctrl.text.trim();
              if (ch.isNotEmpty) {
                ((_cfg['groups'] as List)[groupIdx]['channels'] as List).add(ch);
                await _save();
                if (ctx.mounted) Navigator.pop(ctx);
              }
            },
            child: const Text('Add'),
          ),
        ],
      ),
    );
  }

  Future<void> _addBlockedKeyword() async {
    final ctrl = TextEditingController();
    await showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Blocked Word Add Karein'),
        content: TextField(
          controller: ctrl,
          decoration: const InputDecoration(labelText: 'Keyword (e.g. prank)'),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('Cancel')),
          FilledButton(
            onPressed: () async {
              final w = ctrl.text.trim().toLowerCase();
              if (w.isNotEmpty) {
                (_cfg['blockedTitleKeywords'] as List).add(w);
                await _save();
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

import 'dart:async';
import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const YTGuardApp());
}

class YTGuardApp extends StatelessWidget {
  const YTGuardApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'YT Guard',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        useMaterial3: true,
        scaffoldBackgroundColor: const Color(0xFFF8FAFC),
        colorScheme: const ColorScheme.light(
          primary: Color(0xFF0F172A),
          onPrimary: Color(0xFFFFFFFF),
          secondary: Color(0xFF334155),
          surface: Color(0xFFFFFFFF),
          onSurface: Color(0xFF0F172A),
          error: Color(0xFFB91C1C),
        ),
        appBarTheme: const AppBarTheme(
          backgroundColor: Color(0xFFFFFFFF),
          foregroundColor: Color(0xFF0F172A),
          elevation: 0,
          centerTitle: false,
          titleTextStyle: TextStyle(
            color: Color(0xFF0F172A),
            fontSize: 18,
            fontWeight: FontWeight.w700,
            letterSpacing: -0.3,
          ),
        ),
        cardTheme: CardThemeData(
          color: const Color(0xFFFFFFFF),
          elevation: 0,
          margin: const EdgeInsets.only(bottom: 12),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(10),
            side: const BorderSide(color: Color(0xFFE2E8F0), width: 1),
          ),
        ),
      ),
      home: const MainDashboard(),
    );
  }
}

class MainDashboard extends StatefulWidget {
  const MainDashboard({super.key});

  @override
  State<MainDashboard> createState() => _MainDashboardState();
}

class _MainDashboardState extends State<MainDashboard> with WidgetsBindingObserver {
  static const MethodChannel _channel = MethodChannel('com.rohit.ytguard/channel');

  int _selectedTab = 0;
  bool _isServiceEnabled = false;
  bool _isLoading = true;
  Timer? _uiTimer;

  late Map<String, dynamic> _config;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _config = _defaultConfig();
    _loadState();
    _uiTimer = Timer.periodic(const Duration(seconds: 1), (_) {
      if (mounted) setState(() {});
    });
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _uiTimer?.cancel();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _loadState();
    }
  }

  Map<String, dynamic> _defaultConfig() {
    return {
      "passwords": {
        "family": "1111",
        "shorts": "2222",
        "admin": "3333",
        "switchGroup": "4444",
        "interest": "5555",
      },
      "familyModeExpiry": 0,
      "shortsUnlockExpiry": 0,
      "emergencyPassExpiry": 0,
      "tamperProtection": true,
      "enforceShortsBlock": true,
      "enforceWhitelist": true,
      "enforceTitleBlacklist": true,
      "enforceInterestFilter": false,
      "activeGroupId": "study",
      "blockedTitleKeywords": [
        "vlog",
        "roast",
        "prank",
        "controversy",
        "reaction",
        "gossip"
      ],
      "groups": [
        {
          "id": "study",
          "name": "B.Sc & Defence Study",
          "channels": [
            "Physics Wallah",
            "Maths Jugad Se",
            "Defence Wallah"
          ],
          "interests": [
            "Physics",
            "Mathematics",
            "Chemistry",
            "NDA",
            "CDS",
            "CHSL",
            "B.Sc"
          ]
        },
        {
          "id": "coding",
          "name": "Coding & Full-Stack",
          "channels": [
            "Flutter",
            "Fireship",
            "Traversy Media",
            "CodeWithHarry",
            "Hitesh Choudhary"
          ],
          "interests": [
            "Flutter",
            "Kotlin",
            "JavaScript",
            "Python",
            "Supabase",
            "Security"
          ]
        }
      ]
    };
  }

  Future<void> _loadState() async {
    try {
      final res = await _channel.invokeMethod>('getState');
      if (res != null) {
        final rawJson = res['configJson'] as String?;
        final enabled = res['isServiceEnabled'] as bool? ?? false;

        if (rawJson != null && rawJson.isNotEmpty) {
          _config = jsonDecode(rawJson) as Map<String, dynamic>;
        } else {
          _config = _defaultConfig();
          await _saveConfig();
        }
        if (mounted) {
          setState(() {
            _isServiceEnabled = enabled;
            _isLoading = false;
          });
        }
      }
    } catch (_) {
      if (mounted) {
        setState(() => _isLoading = false);
      }
    }
  }

  Future<void> _saveConfig() async {
    try {
      final raw = jsonEncode(_config);
      await _channel.invokeMethod('saveConfig', {'configJson': raw});
      if (mounted) setState(() {});
    } catch (_) {}
  }

  Future<void> _openAccessibilitySettings() async {
    try {
      await _channel.invokeMethod('openAccessibilitySettings');
    } catch (_) {}
  }

  int get _now => DateTime.now().millisecondsSinceEpoch;

  bool get _isFamilyModeActive => (_config['familyModeExpiry'] as int? ?? 0) > _now;
  bool get _isShortsUnlocked => (_config['shortsUnlockExpiry'] as int? ?? 0) > _now;
  bool get _isEmergencyPassActive => (_config['emergencyPassExpiry'] as int? ?? 0) > _now;

  String _formatRemaining(int expiryMillis) {
    final diff = expiryMillis - _now;
    if (diff <= 0) return "00:00";
    final totalSeconds = diff ~/ 1000;
    final mins = totalSeconds ~/ 60;
    final secs = totalSeconds % 60;
    return "${mins.toString().padLeft(2, '0')}m ${secs.toString().padLeft(2, '0')}s";
  }

  Future<bool> _verifyPasswordDialog({
    required String slotKey,
    required String title,
    required String subtitle,
  }) async {
    final controller = TextEditingController();
    String? errorText;

    final passwords = Map<String, dynamic>.from(_config['passwords'] as Map);
    final expected = (passwords[slotKey] ?? '').toString();

    final result = await showDialog<bool>(
      context: context,
      barrierDismissible: false,
      builder: (ctx) {
        return StatefulBuilder(
          builder: (ctx, setDialogState) {
            return AlertDialog(
              backgroundColor: const Color(0xFFFFFFFF),
              surfaceTintColor: Colors.transparent,
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(10),
                side: const BorderSide(color: Color(0xFFCBD5E1)),
              ),
              title: Text(
                title,
                style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w700),
              ),
              content: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    subtitle,
                    style: const TextStyle(fontSize: 13, color: Color(0xFF475569)),
                  ),
                  const SizedBox(height: 14),
                  TextField(
                    controller: controller,
                    obscureText: true,
                    autofocus: true,
                    decoration: InputDecoration(
                      labelText: 'Enter Password',
                      errorText: errorText,
                      isDense: true,
                      border: OutlineInputBorder(
                        borderRadius: BorderRadius.circular(8),
                      ),
                    ),
                  ),
                ],
              ),
              actions: [
                TextButton(
                  onPressed: () => Navigator.of(ctx).pop(false),
                  child: const Text('Cancel', style: TextStyle(color: Color(0xFF64748B))),
                ),
                FilledButton(
                  onPressed: () {
                    if (controller.text.trim() == expected) {
                      Navigator.of(ctx).pop(true);
                    } else {
                      setDialogState(() {
                        errorText = 'Incorrect password for this security slot';
                      });
                    }
                  },
                  child: const Text('Verify'),
                ),
              ],
            );
          },
        );
      },
    );

    return result ?? false;
  }

  Future<int?> _selectDurationDialog({
    required String title,
    required List<int> minutesOptions,
  }) async {
    return showDialog<int>(
      context: context,
      builder: (ctx) {
        return AlertDialog(
          backgroundColor: const Color(0xFFFFFFFF),
          surfaceTintColor: Colors.transparent,
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
          title: Text(
            title,
            style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w700),
          ),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            children: minutesOptions.map((mins) {
              return Container(
                width: double.infinity,
                margin: const EdgeInsets.only(bottom: 8),
                child: OutlinedButton(
                  onPressed: () => Navigator.of(ctx).pop(mins),
                  style: OutlinedButton.styleFrom(
                    padding: const EdgeInsets.symmetric(vertical: 12),
                    side: const BorderSide(color: Color(0xFFCBD5E1)),
                    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
                  ),
                  child: Text(
                    '$mins Minutes',
                    style: const TextStyle(
                      color: Color(0xFF0F172A),
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                ),
              );
            }).toList(),
          ),
        );
      },
    );
  }

  Map<String, dynamic> _getActiveGroup() {
    final groups = List>.from(
      (_config['groups'] as List).map((e) => Map<String, dynamic>.from(e as Map)),
    );
    final activeId = _config['activeGroupId'] as String? ?? 'study';
    return groups.firstWhere(
      (g) => g['id'] == activeId,
      orElse: () => groups.first,
    );
  }

  @override
  Widget build(BuildContext context) {
    if (_isLoading) {
      return const Scaffold(
        body: Center(child: CircularProgressIndicator()),
      );
    }

    return Scaffold(
      appBar: AppBar(
        title: const Text('YT GUARD CONTROLLER'),
        bottom: PreferredSize(
          preferredSize: const Size.fromHeight(1),
          child: Container(color: const Color(0xFFE2E8F0), height: 1),
        ),
        actions: [
          Container(
            margin: const EdgeInsets.only(right: 16),
            padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
            decoration: BoxDecoration(
              color: _isFamilyModeActive ? const Color(0xFFFEF3C7) : const Color(0xFFF1F5F9),
              borderRadius: BorderRadius.circular(6),
              border: Border.all(
                color: _isFamilyModeActive ? const Color(0xFFD97706) : const Color(0xFFCBD5E1),
              ),
            ),
            child: Text(
              _isFamilyModeActive ? 'FAMILY MODE' : 'STRICT MODE',
              style: TextStyle(
                fontSize: 11,
                fontWeight: FontWeight.w700,
                color: _isFamilyModeActive ? const Color(0xFF92400E) : const Color(0xFF0F172A),
              ),
            ),
          ),
        ],
      ),
      body: IndexedStack(
        index: _selectedTab,
        children: [
          _buildControlCenterTab(),
          _buildGroupsAndWhitelistTab(),
          _buildPasswordMatrixTab(),
        ],
      ),
      bottomNavigationBar: Container(
        decoration: const BoxDecoration(
          border: Border(top: BorderSide(color: Color(0xFFE2E8F0), width: 1)),
        ),
        child: NavigationBar(
          backgroundColor: const Color(0xFFFFFFFF),
          indicatorColor: const Color(0xFFE2E8F0),
          selectedIndex: _selectedTab,
          onDestinationSelected: (idx) => setState(() => _selectedTab = idx),
          destinations: const [
            NavigationDestination(
              icon: Icon(Icons.shield_outlined),
              selectedIcon: Icon(Icons.shield),
              label: 'Controls',
            ),
            NavigationDestination(
              icon: Icon(Icons.layers_outlined),
              selectedIcon: Icon(Icons.layers),
              label: 'Groups & Rules',
            ),
            NavigationDestination(
              icon: Icon(Icons.key_outlined),
              selectedIcon: Icon(Icons.key),
              label: 'Passwords',
            ),
          ],
        ),
      ),
    );
  }

  // ===========================================================================
  // TAB 1: CONTROL CENTER
  // ===========================================================================
  Widget _buildControlCenterTab() {
    final activeGroup = _getActiveGroup();

    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        // Accessibility Service Status Banner
        Card(
          color: _isServiceEnabled ? const Color(0xFFF8FAFC) : const Color(0xFFFEF2F2),
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Row(
              children: [
                Icon(
                  _isServiceEnabled ? Icons.verified_user : Icons.warning_amber_rounded,
                  color: _isServiceEnabled ? const Color(0xFF0F172A) : const Color(0xFFB91C1C),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        _isServiceEnabled
                            ? 'Accessibility Engine Active'
                            : 'Accessibility Permission Required',
                        style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 14),
                      ),
                      const SizedBox(height: 2),
                      Text(
                        _isServiceEnabled
                            ? 'Monitoring official YouTube app in real time.'
                            : 'Enable YT Guard in Accessibility Settings to enforce locks.',
                        style: const TextStyle(fontSize: 12, color: Color(0xFF475569)),
                      ),
                    ],
                  ),
                ),
                if (!_isServiceEnabled)
                  FilledButton(
                    onPressed: _openAccessibilitySettings,
                    child: const Text('Enable'),
                  ),
              ],
            ),
          ),
        ),

        // Family Mode Card
        Card(
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    const Text(
                      'Family Mode (Unrestricted YouTube)',
                      style: TextStyle(fontWeight: FontWeight.w700, fontSize: 15),
                    ),
                    Text(
                      _isFamilyModeActive
                          ? _formatRemaining(_config['familyModeExpiry'] as int)
                          : 'LOCKED',
                      style: TextStyle(
                        fontWeight: FontWeight.w700,
                        fontSize: 12,
                        color: _isFamilyModeActive
                            ? const Color(0xFFB45309)
                            : const Color(0xFF475569),
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 6),
                const Text(
                  'Temporarily disables all YouTube restrictions for family members. Auto-locks when timer ends or if phone screen stays off for 5 minutes.',
                  style: TextStyle(fontSize: 12, color: Color(0xFF475569)),
                ),
                const SizedBox(height: 14),
                SizedBox(
                  width: double.infinity,
                  child: _isFamilyModeActive
                      ? FilledButton.icon(
                          onPressed: () {
                            // One-Way Lock: Zero password required to lock back!
                            _config['familyModeExpiry'] = 0;
                            _saveConfig();
                          },
                          icon: const Icon(Icons.lock, size: 18),
                          label: const Text('Lock Into Study Mode Now (No Password)'),
                        )
                      : OutlinedButton.icon(
                          onPressed: () async {
                            final ok = await _verifyPasswordDialog(
                              slotKey: 'family',
                              title: 'Unlock Family Mode',
                              subtitle: 'Requires Password #1 (Family Mode Password).',
                            );
                            if (!ok) return;
                            final mins = await _selectDurationDialog(
                              title: 'Select Family Mode Duration',
                              minutesOptions: [15, 30, 60, 120, 180],
                            );
                            if (mins != null) {
                              _config['familyModeExpiry'] = _now + (mins * 60 * 1000);
                              await _saveConfig();
                            }
                          },
                          icon: const Icon(Icons.lock_open, size: 18),
                          label: const Text('Unlock for Family (Password #1)'),
                        ),
                ),
              ],
            ),
          ),
        ),

        // YouTube Shorts Lock Card
        Card(
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    const Text(
                      'YouTube Shorts Blocker',
                      style: TextStyle(fontWeight: FontWeight.w700, fontSize: 15),
                    ),
                    Text(
                      _isShortsUnlocked
                          ? 'UNLOCKED (${_formatRemaining(_config['shortsUnlockExpiry'] as int)})'
                          : 'BLOCKED',
                      style: TextStyle(
                        fontWeight: FontWeight.w700,
                        fontSize: 12,
                        color: _isShortsUnlocked
                            ? const Color(0xFFB91C1C)
                            : const Color(0xFF0F172A),
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 6),
                const Text(
                  'Blocks Shorts tab, Shorts player, and vertical reel feeds everywhere inside the YouTube app.',
                  style: TextStyle(fontSize: 12, color: Color(0xFF475569)),
                ),
                const SizedBox(height: 14),
                SizedBox(
                  width: double.infinity,
                  child: _isShortsUnlocked
                      ? FilledButton.icon(
                          onPressed: () {
                            _config['shortsUnlockExpiry'] = 0;
                            _config['enforceShortsBlock'] = true;
                            _saveConfig();
                          },
                          icon: const Icon(Icons.lock, size: 18),
                          label: const Text('Lock Shorts Immediately (No Password)'),
                        )
                      : OutlinedButton.icon(
                          onPressed: () async {
                            final ok = await _verifyPasswordDialog(
                              slotKey: 'shorts',
                              title: 'Unlock YouTube Shorts',
                              subtitle: 'Requires Password #2 (Shorts Vault Password).',
                            );
                            if (!ok) return;
                            final mins = await _selectDurationDialog(
                              title: 'Unlock Shorts Duration',
                              minutesOptions: [10, 20, 30, 60],
                            );
                            if (mins != null) {
                              _config['shortsUnlockExpiry'] = _now + (mins * 60 * 1000);
                              await _saveConfig();
                            }
                          },
                          icon: const Icon(Icons.lock_open, size: 18),
                          label: const Text('Temporarily Unlock Shorts (Password #2)'),
                        ),
                ),
              ],
            ),
          ),
        ),

        // Active Profile & Emergency 1-Video Pass Card
        Card(
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  'Active Group: ${activeGroup['name']}',
                  style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 15),
                ),
                const SizedBox(height: 4),
                Text(
                  'Whitelisted Channels: ${(activeGroup['channels'] as List).length}  •  Interest Topics: ${(activeGroup['interests'] as List).length}',
                  style: const TextStyle(fontSize: 12, color: Color(0xFF475569)),
                ),
                const Divider(height: 24, color: Color(0xFFE2E8F0)),
                Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    const Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            'Emergency Single-Video Pass (15 Min)',
                            style: TextStyle(fontWeight: FontWeight.w600, fontSize: 13),
                          ),
                          SizedBox(height: 2),
                          Text(
                            'Allows watching an educational/troubleshooting video from a non-whitelisted channel for 15 minutes (Shorts & blacklisted words remain blocked).',
                            style: TextStyle(fontSize: 11, color: Color(0xFF64748B)),
                          ),
                        ],
                      ),
                    ),
                    const SizedBox(width: 12),
                    _isEmergencyPassActive
                        ? FilledButton(
                            onPressed: () {
                              _config['emergencyPassExpiry'] = 0;
                              _saveConfig();
                            },
                            child: Text(
                              'Lock (${_formatRemaining(_config['emergencyPassExpiry'] as int)})',
                            ),
                          )
                        : OutlinedButton(
                            onPressed: () async {
                              final ok = await _verifyPasswordDialog(
                                slotKey: 'interest',
                                title: 'Activate 15-Min Video Pass',
                                subtitle: 'Requires Password #5 (Interest / Pass Password).',
                              );
                              if (ok) {
                                _config['emergencyPassExpiry'] = _now + (15 * 60 * 1000);
                                await _saveConfig();
                              }
                            },
                            child: const Text('15m Pass'),
                          ),
                  ],
                ),
              ],
            ),
          ),
        ),

        // Settings Tamper Shield Card
        Card(
          child: SwitchListTile(
            title: const Text(
              'Settings & App Info Tamper Shield',
              style: TextStyle(fontWeight: FontWeight.w700, fontSize: 14),
            ),
            subtitle: const Text(
              'Blocks Force Stop, Clear Data, and Uninstall screens for YouTube and YT Guard in Android Settings.',
              style: TextStyle(fontSize: 12, color: Color(0xFF475569)),
            ),
            value: _config['tamperProtection'] as bool? ?? true,
            activeColor: const Color(0xFF0F172A),
            onChanged: (val) async {
              if (val) {
                // Turning lock ON requires no password
                _config['tamperProtection'] = true;
                await _saveConfig();
              } else {
                final ok = await _verifyPasswordDialog(
                  slotKey: 'admin',
                  title: 'Disable Tamper Shield',
                  subtitle: 'Requires Password #3 (Admin Password).',
                );
                if (ok) {
                  _config['tamperProtection'] = false;
                  await _saveConfig();
                }
              }
            },
          ),
        ),
      ],
    );
  }

  // ===========================================================================
  // TAB 2: GROUPS, WHITELIST & CONTENT FILTERS
  // ===========================================================================
  Widget _buildGroupsAndWhitelistTab() {
    final groups = List>.from(
      (_config['groups'] as List).map((e) => Map<String, dynamic>.from(e as Map)),
    );
    final activeGroup = _getActiveGroup();
    final channels = List<String>.from(activeGroup['channels'] as List);
    final interests = List<String>.from(activeGroup['interests'] as List);
    final blockedKeywords = List<String>.from(_config['blockedTitleKeywords'] as List);

    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        // Group Switcher Card
        Card(
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    const Text(
                      'Active Study / Focus Group',
                      style: TextStyle(fontWeight: FontWeight.w700, fontSize: 15),
                    ),
                    TextButton.icon(
                      onPressed: _createNewGroupDialog,
                      icon: const Icon(Icons.add, size: 16),
                      label: const Text('New Group (Pwd #3)'),
                    ),
                  ],
                ),
                const SizedBox(height: 8),
                Wrap(
                  spacing: 8,
                  runSpacing: 8,
                  children: groups.map((g) {
                    final isSelected = g['id'] == activeGroup['id'];
                    return ChoiceChip(
                      label: Text(g['name'].toString()),
                      selected: isSelected,
                      onSelected: (selected) async {
                        if (!selected || isSelected) return;
                        final ok = await _verifyPasswordDialog(
                          slotKey: 'switchGroup',
                          title: 'Switch Active Group',
                          subtitle: 'Requires Password #4 (Group Switch Password).',
                        );
                        if (ok) {
                          _config['activeGroupId'] = g['id'];
                          await _saveConfig();
                        }
                      },
                    );
                  }).toList(),
                ),
              ],
            ),
          ),
        ),

        // Enforcement Rules Card
        Card(
          child: Column(
            children: [
              SwitchListTile(
                title: const Text(
                  'Enforce Channel Whitelist',
                  style: TextStyle(fontWeight: FontWeight.w700, fontSize: 14),
                ),
                subtitle: const Text(
                  'Only channels listed in the active group are allowed to play videos.',
                  style: TextStyle(fontSize: 12),
                ),
                value: _config['enforceWhitelist'] as bool? ?? true,
                onChanged: (val) async {
                  if (val) {
                    _config['enforceWhitelist'] = true;
                    await _saveConfig();
                  } else {
                    final ok = await _verifyPasswordDialog(
                      slotKey: 'admin',
                      title: 'Disable Channel Whitelist',
                      subtitle: 'Requires Password #3 (Group Admin Password).',
                    );
                    if (ok) {
                      _config['enforceWhitelist'] = false;
                      await _saveConfig();
                    }
                  }
                },
              ),
              const Divider(height: 1, color: Color(0xFFE2E8F0)),
              SwitchListTile(
                title: const Text(
                  'Enforce Interest / Topic Keywords',
                  style: TextStyle(fontWeight: FontWeight.w700, fontSize: 14),
                ),
                subtitle: const Text(
                  'Requires video screen text to match at least one interest topic of the active group.',
                  style: TextStyle(fontSize: 12),
                ),
                value: _config['enforceInterestFilter'] as bool? ?? false,
                onChanged: (val) async {
                  if (val) {
                    _config['enforceInterestFilter'] = true;
                    await _saveConfig();
                  } else {
                    final ok = await _verifyPasswordDialog(
                      slotKey: 'interest',
                      title: 'Disable Interest Topic Filter',
                      subtitle: 'Requires Password #5 (Interest Filter Password).',
                    );
                    if (ok) {
                      _config['enforceInterestFilter'] = false;
                      await _saveConfig();
                    }
                  }
                },
              ),
              const Divider(height: 1, color: Color(0xFFE2E8F0)),
              SwitchListTile(
                title: const Text(
                  'Enforce Title Keyword Blacklist',
                  style: TextStyle(fontWeight: FontWeight.w700, fontSize: 14),
                ),
                subtitle: const Text(
                  'Blocks videos containing distracting words (e.g. vlog, roast) even on whitelisted channels.',
                  style: TextStyle(fontSize: 12),
                ),
                value: _config['enforceTitleBlacklist'] as bool? ?? true,
                onChanged: (val) async {
                  if (val) {
                    _config['enforceTitleBlacklist'] = true;
                    await _saveConfig();
                  } else {
                    final ok = await _verifyPasswordDialog(
                      slotKey: 'admin',
                      title: 'Disable Keyword Blacklist',
                      subtitle: 'Requires Password #3 (Group Admin Password).',
                    );
                    if (ok) {
                      _config['enforceTitleBlacklist'] = false;
                      await _saveConfig();
                    }
                  }
                },
              ),
            ],
          ),
        ),

        // Whitelisted Channels Editor
        _buildEditableChipSection(
          title: 'Whitelisted Channels (${activeGroup['name']})',
          subtitle: 'Exact or partial YouTube channel names allowed in this group.',
          items: channels,
          addPasswordSlot: 'admin',
          removePasswordSlot: 'admin',
          onAdd: (val) {
            channels.add(val);
            _updateActiveGroupField('channels', channels);
          },
          onRemove: (val) {
            channels.remove(val);
            _updateActiveGroupField('channels', channels);
          },
        ),

        // Allowed Interest Keywords Editor
        _buildEditableChipSection(
          title: 'Allowed Interest Topics (${activeGroup['name']})',
          subtitle: 'Topic keywords allowed when Interest Filter is ON.',
          items: interests,
          addPasswordSlot: 'interest',
          removePasswordSlot: 'interest',
          onAdd: (val) {
            interests.add(val);
            _updateActiveGroupField('interests', interests);
          },
          onRemove: (val) {
            interests.remove(val);
            _updateActiveGroupField('interests', interests);
          },
        ),

        // Blocked Title Keywords Editor (One-Way Add: adding blocked word needs no password!)
        _buildEditableChipSection(
          title: 'Blocked Title Keywords (Global)',
          subtitle: 'Adding a blocked keyword requires NO password. Removing requires Admin Password (#3).',
          items: blockedKeywords,
          addPasswordSlot: null, // Free to add stricter rules anytime
          removePasswordSlot: 'admin',
          onAdd: (val) {
            blockedKeywords.add(val.toLowerCase());
            _config['blockedTitleKeywords'] = blockedKeywords;
            _saveConfig();
          },
          onRemove: (val) {
            blockedKeywords.remove(val);
            _config['blockedTitleKeywords'] = blockedKeywords;
            _saveConfig();
          },
        ),
      ],
    );
  }

  void _updateActiveGroupField(String key, List<String> updated) {
    final groups = List>.from(
      (_config['groups'] as List).map((e) => Map<String, dynamic>.from(e as Map)),
    );
    final activeId = _config['activeGroupId'] as String? ?? 'study';
    for (int i = 0; i < groups.length; i++) {
      if (groups[i]['id'] == activeId) {
        groups[i][key] = updated;
        break;
      }
    }
    _config['groups'] = groups;
    _saveConfig();
  }

  Future<void> _createNewGroupDialog() async {
    final ok = await _verifyPasswordDialog(
      slotKey: 'admin',
      title: 'Create New Group',
      subtitle: 'Requires Password #3 (Group Admin Password).',
    );
    if (!ok) return;

    final controller = TextEditingController();
    final name = await showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('New Group Name', style: TextStyle(fontSize: 16, fontWeight: FontWeight.w700)),
        content: TextField(
          controller: controller,
          autofocus: true,
          decoration: const InputDecoration(
            hintText: 'e.g. Revision / Exam Prep',
            border: OutlineInputBorder(),
          ),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('Cancel')),
          FilledButton(
            onPressed: () => Navigator.pop(ctx, controller.text.trim()),
            child: const Text('Create'),
          ),
        ],
      ),
    );

    if (name != null && name.isNotEmpty) {
      final groups = List>.from(
        (_config['groups'] as List).map((e) => Map<String, dynamic>.from(e as Map)),
      );
      final newId = 'group_${DateTime.now().millisecondsSinceEpoch}';
      groups.add({
        'id': newId,
        'name': name,
        'channels': <String>[],
        'interests': <String>[],
      });
      _config['groups'] = groups;
      _config['activeGroupId'] = newId;
      await _saveConfig();
    }
  }

  Widget _buildEditableChipSection({
    required String title,
    required String subtitle,
    required List<String> items,
    required String? addPasswordSlot,
    required String removePasswordSlot,
    required void Function(String) onAdd,
    required void Function(String) onRemove,
  }) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Expanded(
                  child: Text(
                    title,
                    style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 14),
                  ),
                ),
                OutlinedButton.icon(
                  onPressed: () async {
                    if (addPasswordSlot != null) {
                      final ok = await _verifyPasswordDialog(
                        slotKey: addPasswordSlot,
                        title: 'Authenticate to Add Item',
                        subtitle: 'Enter the assigned slot password to modify this list.',
                      );
                      if (!ok) return;
                    }
                    final ctrl = TextEditingController();
                    if (!mounted) return;
                    final val = await showDialog<String>(
                      context: context,
                      builder: (ctx) => AlertDialog(
                        title: Text(title, style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w700)),
                        content: TextField(
                          controller: ctrl,
                          autofocus: true,
                          decoration: const InputDecoration(
                            hintText: 'Enter channel or keyword',
                            border: OutlineInputBorder(),
                          ),
                        ),
                        actions: [
                          TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('Cancel')),
                          FilledButton(
                            onPressed: () => Navigator.pop(ctx, ctrl.text.trim()),
                            child: const Text('Add'),
                          ),
                        ],
                      ),
                    );
                    if (val != null && val.isNotEmpty) {
                      onAdd(val);
                    }
                  },
                  icon: const Icon(Icons.add, size: 16),
                  label: const Text('Add'),
                ),
              ],
            ),
            const SizedBox(height: 4),
            Text(subtitle, style: const TextStyle(fontSize: 12, color: Color(0xFF64748B))),
            const SizedBox(height: 12),
            items.isEmpty
                ? const Text('No items added yet.', style: TextStyle(fontSize: 12, color: Color(0xFF94A3B8)))
                : Wrap(
                    spacing: 8,
                    runSpacing: 8,
                    children: items.map((item) {
                      return InputChip(
                        label: Text(item, style: const TextStyle(fontSize: 12)),
                        onDeleted: () async {
                          final ok = await _verifyPasswordDialog(
                            slotKey: removePasswordSlot,
                            title: 'Remove "$item"',
                            subtitle: 'Requires slot password to remove this item.',
                          );
                          if (ok) {
                            onRemove(item);
                          }
                        },
                      );
                    }).toList(),
                  ),
          ],
        ),
      ),
    );
  }

  // ===========================================================================
  // TAB 3: 5-PASSWORD MATRIX  (INDEPENDENT VAULT SLOTS) Rajput 
  // ===========================================================================
  Widget _buildPasswordMatrixTab() {
    final slots = [
      {
        'key': 'family',
        'title': 'Password #1: Family Mode Unlock',
        'desc': 'Unlocks full YouTube for family members with auto-lock timer. Default: 1111',
      },
      {
        'key': 'shorts',
        'title': 'Password #2: YouTube Shorts Vault',
        'desc': 'Required only when you want to unlock YouTube Shorts. Store this in your longest Time Vault. Default: 2222',
      },
      {
        'key': 'admin',
        'title': 'Password #3: Group & Whitelist Admin',
        'desc': 'Required to edit whitelisted channels, disable Tamper Shield, or remove blocked keywords. Default: 3333',
      },
      {
        'key': 'switchGroup',
        'title': 'Password #4: Group Switcher',
        'desc': 'Required to switch between active groups (e.g. Study Group to Coding Group). Default: 4444',
      },
      {
        'key': 'interest',
        'title': 'Password #5: Interest Filter & 15m Pass',
        'desc': 'Controls Home/Topic interest keywords and the 15-minute Single-Video Emergency Pass. Default: 5555',
      },
    ];

    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        const Card(
          color: Color(0xFFF1F5F9),
          child: Padding(
            padding: EdgeInsets.all(14),
            child: Text(
              'Zero-Bypass Isolation: Each security layer has its own independent password. Changing any password requires entering that specific slot\'s current password first—so knowing one password never compromises the others.',
              style: TextStyle(fontSize: 12, color: Color(0xFF334155), height: 1.4),
            ),
          ),
        ),
        ...slots.map((slot) {
          final slotKey = slot['key']!;
          return Card(
            child: ListTile(
              contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
              title: Text(
                slot['title']!,
                style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 14),
              ),
              subtitle: Padding(
                padding: const EdgeInsets.only(top: 4),
                child: Text(
                  slot['desc']!,
                  style: const TextStyle(fontSize: 12, color: Color(0xFF475569)),
                ),
              ),
              trailing: OutlinedButton(
                onPressed: () => _changeSlotPasswordDialog(slotKey, slot['title']!),
                child: const Text('Change'),
              ),
            ),
          );
        }),
      ],
    );
  }

  Future<void> _changeSlotPasswordDialog(String slotKey, String slotTitle) async {
    final verified = await _verifyPasswordDialog(
      slotKey: slotKey,
      title: 'Verify Current Password',
      subtitle: 'Enter current password for [$slotTitle] before setting a new one.',
    );
    if (!verified || !mounted) return;

    final newPassCtrl = TextEditingController();
    final confirmCtrl = TextEditingController();
    String? err;

    await showDialog<void>(
      context: context,
      barrierDismissible: false,
      builder: (ctx) {
        return StatefulBuilder(
          builder: (ctx, setDialogState) {
            return AlertDialog(
              title: Text(
                'Set New $slotTitle',
                style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w700),
              ),
              content: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  TextField(
                    controller: newPassCtrl,
                    obscureText: true,
                    decoration: const InputDecoration(
                      labelText: 'New Password (min 4 chars)',
                      border: OutlineInputBorder(),
                      isDense: true,
                    ),
                  ),
                  const SizedBox(height: 12),
                  TextField(
                    controller: confirmCtrl,
                    obscureText: true,
                    decoration: InputDecoration(
                      labelText: 'Confirm New Password',
                      errorText: err,
                      border: const OutlineInputBorder(),
                      isDense: true,
                    ),
                  ),
                ],
              ),
              actions: [
                TextButton(
                  onPressed: () => Navigator.pop(ctx),
                  child: const Text('Cancel'),
                ),
                FilledButton(
                  onPressed: () {
                    final p1 = newPassCtrl.text.trim();
                    final p2 = confirmCtrl.text.trim();
                    if (p1.length < 4) {
                      setDialogState(() => err = 'Minimum 4 characters required');
                      return;
                    }
                    if (p1 != p2) {
                      setDialogState(() => err = 'Passwords do not match');
                      return;
                    }
                    final passwords = Map<String, dynamic>.from(_config['passwords'] as Map);
                    passwords[slotKey] = p1;
                    _config['passwords'] = passwords;
                    _saveConfig();
                    Navigator.pop(ctx);
                  },
                  child: const Text('Save Password'),
                ),
              ],
            );
          },
        );
      },
    );
  }
}

class UserInfo {
  final String email;
  final double transferEnable;
  final int? lastLoginAt; // 允许为 null
  final int createdAt;
  final bool banned; // 账户状态, true: 被封禁, false: 正常
  final bool remindExpire;
  final bool remindTraffic;
  final int? expiredAt; // 允许为 null
  final double balance; // 消费余额
  final double commissionBalance; // 剩余佣金余额
  final int planId;
  final double? discount; // 允许为 null
  final double? commissionRate; // 允许为 null
  final String? telegramId; // 允许为 null
  final String uuid;
  final String avatarUrl;

  UserInfo({
    required this.email,
    required this.transferEnable,
    this.lastLoginAt,
    required this.createdAt,
    required this.banned,
    required this.remindExpire,
    required this.remindTraffic,
    this.expiredAt,
    required this.balance,
    required this.commissionBalance,
    required this.planId,
    this.discount,
    this.commissionRate,
    this.telegramId,
    required this.uuid,
    required this.avatarUrl,
  });

  // 从 JSON 创建 UserInfo 实例
factory UserInfo.fromJson(Map<String, dynamic> json) {
  return UserInfo(
    email: json['email'] as String? ?? '',
    transferEnable: (json['transfer_enable'] as num?)?.toDouble() ?? 0.0,
    lastLoginAt: json['last_login_at'] as int?,
    createdAt: json['created_at'] as int? ?? 0,

    // 优化版 bool 解析：支持 null、bool、int(1/0) 三种情况
    banned: _parseBool(json['banned'], defaultVal: false),
    remindExpire: _parseBool(json['remind_expire'], defaultVal: false),
    remindTraffic: _parseBool(json['remind_traffic'], defaultVal: false),

    expiredAt: json['expired_at'] as int?,
    balance: (json['balance'] as num?)?.toDouble() ?? 0.0,
    commissionBalance: (json['commission_balance'] as num?)?.toDouble() ?? 0.0,
    planId: json['plan_id'] as int? ?? 0,
    discount: (json['discount'] as num?)?.toDouble(),
    commissionRate: (json['commission_rate'] as num?)?.toDouble(),
    telegramId: json['telegram_id'] as String?,
    uuid: json['uuid'] as String? ?? '',
    avatarUrl: json['avatar_url'] as String? ?? '',
  );
  }

/// 辅助方法：解析可能为 null、bool 或 int(1/0) 的字段
static bool _parseBool(dynamic value, {bool defaultVal = false}) {
  if (value == null) return defaultVal; // 如果字段不存在或为 null，返回默认值
  if (value is bool) return value;     // 如果已经是 bool，直接返回
  if (value is int) return value == 1; // 如果是 int(1/0)，转换成 bool
  return defaultVal;                   // 其他情况（如非法类型），返回默认值
  }
}

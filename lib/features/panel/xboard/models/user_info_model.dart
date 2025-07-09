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

    // 仅修改 bool 字段的解析逻辑（直接解析 bool，null 时默认 false）
    banned: json['banned'] as bool? ?? false,
    remindExpire: json['remind_expire'] as bool? ?? false,
    remindTraffic: json['remind_traffic'] as bool? ?? false,

    // 其他字段保持原有逻辑不变
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
}

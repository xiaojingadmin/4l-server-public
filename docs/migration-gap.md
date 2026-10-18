# 未迁移接口清单（5.1.0）

Python 版快照共 **315** 条路径，Java 版已注册实现 **101** 条，
尚未迁移 **214** 条。以下按功能域分组（对照 Python 版 `wire_*.py` 模块）。

> 全部未迁移路径在客户端看来是显式 501（`Local endpoint not implemented`），
> 不会用空对象伪装成功。排查日志可区分「还没移植」与「路径不存在」。

## /v1/activity_record（4 条）—— 活动记录：广告播放数据、月度展示状态

- `/v1/activity_record/get_top`
- `/v1/activity_record/notify_month_displayed`
- `/v1/activity_record/play_adv_data`
- `/v1/activity_record/set_watchable_status`

## /v1/archive（19 条）—— 档案/活动回放（含 With×Live、Fes×Live 历史数据与购票）

- `/v1/archive/get_archive_list`
- `/v1/archive/get_channel_list`
- `/v1/archive/get_channel_movie_list`
- `/v1/archive/get_fes_archive_data`
- `/v1/archive/get_fes_timeline_data`
- `/v1/archive/get_home`
- `/v1/archive/get_ranking_list`
- `/v1/archive/get_season_list`
- `/v1/archive/get_with_archive_data`
- `/v1/archive/get_with_station_data`
- `/v1/archive/get_with_station_list`
- `/v1/archive/set_cancel_recommend_channel`
- `/v1/archive/set_fes_camera`
- `/v1/archive/set_purchase_ticket`
- `/v1/archive/set_recommend_channel`
- `/v1/archive/withlive_gift`
- `/v1/archive/withlive_info`
- `/v1/archive/withlive_prize`
- `/v1/archive/withlive_stars`

## /v1/beginner_mission（4 条）—— 新手任务：列表与奖励领取

- `/v1/beginner_mission/get_list`
- `/v1/beginner_mission/set_banner_reward`
- `/v1/beginner_mission/set_reward`
- `/v1/beginner_mission/set_reward_all`

## /v1/card（2 条）—— 卡片展示设置（get/set_display_data）

- `/v1/card/get_display_data`
- `/v1/card/set_display_data`

## /v1/chapter（1 条）—— 剧情章节首页

- `/v1/chapter/home`

## /v1/circle（26 条）—— 社团（Circle）全量：创建/加入/聊天/捐献/管理，共 24 条

- `/v1/circle/get_chat_log_list`
- `/v1/circle/get_circle_top_info`
- `/v1/circle/get_detail`
- `/v1/circle/get_info`
- `/v1/circle/get_invite_and_join_info`
- `/v1/circle/get_invite_list`
- `/v1/circle/get_list`
- `/v1/circle/set_approve_invite`
- `/v1/circle/set_approve_join`
- `/v1/circle/set_cancel_invite`
- `/v1/circle/set_cancel_join`
- `/v1/circle/set_chat_message`
- `/v1/circle/set_create`
- `/v1/circle/set_dismissal`
- `/v1/circle/set_dissolution`
- `/v1/circle/set_donation`
- `/v1/circle/set_expulsion`
- `/v1/circle/set_invite`
- `/v1/circle/set_item_request`
- `/v1/circle/set_join`
- `/v1/circle/set_out`
- `/v1/circle/set_position`
- `/v1/circle/set_reject_invite`
- `/v1/circle/set_reject_join`
- `/v1/circle/set_setting`
- `/v1/circle/set_transfer_leader`

## /v1/collection（1 条）—— 图鉴收藏列表

- `/v1/collection/get_gallary_list`

## /v1/common（1 条）—— 头部公告（Python 版返回 JSON 数组，契约层暂不支持）

- `/v1/common/get_header_announs`

## /v1/feslive（28 条）—— Fes×Live 全量：进入/聊天/应援/礼物/排名等，共 28 条

- `/v1/feslive/circle_chat/comment`
- `/v1/feslive/clap`
- `/v1/feslive/comment`
- `/v1/feslive/connect_token`
- `/v1/feslive/enquete`
- `/v1/feslive/enter`
- `/v1/feslive/get_gift_shop_list`
- `/v1/feslive/get_list`
- `/v1/feslive/gift`
- `/v1/feslive/gift_pt_rankings`
- `/v1/feslive/grand_prix_ranking`
- `/v1/feslive/live_announcement`
- `/v1/feslive/live_info`
- `/v1/feslive/live_subinfo`
- `/v1/feslive/lobby`
- `/v1/feslive/lobby_announcement`
- `/v1/feslive/lobby_comment`
- `/v1/feslive/lobby_gift`
- `/v1/feslive/lobby_info`
- `/v1/feslive/lobby_message_card`
- `/v1/feslive/lobby_subinfo`
- `/v1/feslive/message_card`
- `/v1/feslive/out_quest_live_ranking`
- `/v1/feslive/penlight_color`
- `/v1/feslive/prize`
- `/v1/feslive/set_camera`
- `/v1/feslive/set_flower_stand`
- `/v1/feslive/set_prize`

## /v1/follow（6 条）—— 关注：列表/搜索/推荐/关注操作

- `/v1/follow/get_follower_list`
- `/v1/follow/get_list`
- `/v1/follow/live_chat_group_list`
- `/v1/follow/recommend_list`
- `/v1/follow/search_id`
- `/v1/follow/set_follow`

## /v1/friend（12 条）—— 好友全量：搜索/申请/批准/解除，共 12 条

- `/v1/friend/get_list`
- `/v1/friend/get_request_list`
- `/v1/friend/search_name`
- `/v1/friend/search_player_id`
- `/v1/friend/search_recommend`
- `/v1/friend/set_approval_request`
- `/v1/friend/set_approval_request_all`
- `/v1/friend/set_break_off`
- `/v1/friend/set_refuse_request_all`
- `/v1/friend/set_request`
- `/v1/friend/set_request_cancel`
- `/v1/friend/update_request_view_history`

## /v1/gacha（8 条）—— 抽卡全量：购买/历史/保底点数交换/选择券交换，共 8 条

- `/v1/gacha/get_exchange_card_having_list`
- `/v1/gacha/get_guarantee_point_list`
- `/v1/gacha/get_history`
- `/v1/gacha/get_lottery_chance`
- `/v1/gacha/get_series_list`
- `/v1/gacha/set_guarantee_point_exchange`
- `/v1/gacha/set_purchase`
- `/v1/gacha/set_select_ticket_exchange`

## /v1/gp_prize_exchange（2 条）—— GP 奖励交换

- `/v1/gp_prize_exchange/get_list`
- `/v1/gp_prize_exchange/set_purchase`

## /v1/in_quest_live（5 条）—— 课内 Live：开始/结算/跳过

- `/v1/in_quest_live/get_live_info`
- `/v1/in_quest_live/set_finish`
- `/v1/in_quest_live/set_retire`
- `/v1/in_quest_live/set_start`
- `/v1/in_quest_live/skip_quest`

## /v1/item_exchange（5 条）—— 道具交换：列表与购买（依赖 shop 限购/解锁结算层）

- `/v1/item_exchange/get_limit_break_material_convert_list`
- `/v1/item_exchange/get_list`
- `/v1/item_exchange/get_list_new`
- `/v1/item_exchange/set_limit_break_material_convert`
- `/v1/item_exchange/set_purchase`

## /v1/mission（2 条）—— 日常任务：列表与奖励领取

- `/v1/mission/get_list`
- `/v1/mission/receive_common_mission_reward`

## /v1/out_quest_live（35 条）—— 课外 Live（ Quest）全量：日常/梦想/等级考/音乐学习/标准，共 32 条

- `/v1/out_quest_live/daily/get_stage_data`
- `/v1/out_quest_live/daily/get_stage_list`
- `/v1/out_quest_live/daily/get_stage_select`
- `/v1/out_quest_live/daily/recovery_challenge_count`
- `/v1/out_quest_live/daily/set_release`
- `/v1/out_quest_live/dream/get_member_select`
- `/v1/out_quest_live/dream/get_result`
- `/v1/out_quest_live/dream/notify_member_release_confirm`
- `/v1/out_quest_live/dream/set_card`
- `/v1/out_quest_live/get_area_select`
- `/v1/out_quest_live/get_live_setting`
- `/v1/out_quest_live/get_play_report`
- `/v1/out_quest_live/get_quest_clear_status_list`
- `/v1/out_quest_live/get_quest_list`
- `/v1/out_quest_live/get_quest_top`
- `/v1/out_quest_live/get_result`
- `/v1/out_quest_live/get_stage_data`
- `/v1/out_quest_live/get_stage_select`
- `/v1/out_quest_live/get_stamina_recovery_info`
- `/v1/out_quest_live/grade/get_live_list`
- `/v1/out_quest_live/grade/get_quest_list`
- `/v1/out_quest_live/grade/get_rank_list`
- `/v1/out_quest_live/grade/get_ranking_list`
- `/v1/out_quest_live/grade/get_stage_data`
- `/v1/out_quest_live/grade/get_stage_list`
- `/v1/out_quest_live/music_learning/get_music_select`
- `/v1/out_quest_live/music_learning/get_result`
- `/v1/out_quest_live/music_learning/set_music`
- `/v1/out_quest_live/recovery_stamina`
- `/v1/out_quest_live/set_area_select_view_hist`
- `/v1/out_quest_live/set_live_setting`
- `/v1/out_quest_live/standard/get_area_select`
- `/v1/out_quest_live/standard/get_stage_data`
- `/v1/out_quest_live/standard/get_stage_select`
- `/v1/out_quest_live/standard/set_area_select_view_hist`

## /v1/petal_exchange（2 条）—— 花瓣交换

- `/v1/petal_exchange/get_list`
- `/v1/petal_exchange/set_purchase`

## /v1/present_box（3 条）—— 礼物盒领取（列表 Java 已有读取，领取写接口未迁移）

- `/v1/present_box/get_list`
- `/v1/present_box/set_item`
- `/v1/present_box/set_item_all`

## /v1/select_ticket_exchange（1 条）—— 选择券交换

- `/v1/select_ticket_exchange/get_list`

## /v1/shop（1 条）—— 商店购买列表（依赖限购/解锁结算层）

- `/v1/shop/get_list`

## /v1/sisca_store（2 条）—— Sisca 商店

- `/v1/sisca_store/get_list`
- `/v1/sisca_store/set_purchase`

## /v1/step_up_beginner_mission（2 条）—— 进阶新手任务：列表与奖励领取

- `/v1/step_up_beginner_mission/get_list`
- `/v1/step_up_beginner_mission/set_reward`

## /v1/sticker_exchange（2 条）—— 贴纸交换

- `/v1/sticker_exchange/get_list`
- `/v1/sticker_exchange/set_purchase`

## /v1/user（10 条）

- `/v1/user/card/check_evolution`
- `/v1/user/card/check_limit_break`
- `/v1/user/card/check_skill_level_up`
- `/v1/user/card/check_style_level_up`
- `/v1/user/card/evolution`
- `/v1/user/card/get_detail_rental`
- `/v1/user/card/limit_break`
- `/v1/user/card/rhythm_game_skill_level_up`
- `/v1/user/card/skill_level_up`
- `/v1/user/card/style_level_up`

## /v1/webview（5 条）—— 内嵌网页视图：抽卡详情/Live 登录/主题列表

- `/v1/webview/gacha/get_detail`
- `/v1/webview/live/enter`
- `/v1/webview/live/live_info`
- `/v1/webview/live/login`
- `/v1/webview/school_idol_connect_post/get_theme_list`

## /v1/withlive（14 条）—— With×Live 全量：进入/聊天/礼物/排名等，共 16 条

- `/v1/withlive/comment`
- `/v1/withlive/connect_token`
- `/v1/withlive/enquetes/answer`
- `/v1/withlive/enter`
- `/v1/withlive/get_gift_shop_list`
- `/v1/withlive/get_list`
- `/v1/withlive/gift`
- `/v1/withlive/gift_pt_rankings`
- `/v1/withlive/live_info`
- `/v1/withlive/live_subinfo`
- `/v1/withlive/message_card`
- `/v1/withlive/prize`
- `/v1/withlive/result_ranking`
- `/v1/withlive/set_star`

## /v1/withstation（11 条）—— With×Station 全量：进入/聊天/礼物/排名等，共 13 条

- `/v1/withstation/comment`
- `/v1/withstation/get_gift_shop_list`
- `/v1/withstation/gift`
- `/v1/withstation/gift_pt_rankings`
- `/v1/withstation/live_info`
- `/v1/withstation/live_subinfo`
- `/v1/withstation/message_card`
- `/v1/withstation/prize`
- `/v1/withstation/result_ranking`
- `/v1/withstation/set_star`
- `/v1/withstation/withstation_info`

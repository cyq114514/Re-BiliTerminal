package com.RobinNotBad.BiliClient.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * 隐私锁的持久化与摘要校验测试。
 *
 * <p>落盘键是用户数据契约（升级不能改名），这里用字符串字面量断言：只存开关与
 * SHA-256 摘要，绝不出现明文密码。
 */
@RunWith(RobolectricTestRunner.class)
public class PrivacyLockUtilTest {

    private static final String KEY_ENABLE = "privacy_lock_enable";
    private static final String KEY_HASH = "privacy_lock_password_hash";
    private static final String HEX64 = "^[0-9a-f]{64}$";

    private SharedPreferences sp;

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        sp = context.getSharedPreferences("test", Context.MODE_PRIVATE);
        sp.edit().clear().commit();
        SharedPreferencesUtil.sharedPreferences = sp;
    }

    @After
    public void tearDown() {
        sp.edit().clear().commit();
        SharedPreferencesUtil.sharedPreferences = null;
    }

    @Test
    public void lockStaysOffUntilPasswordExists() {
        assertFalse(PrivacyLockUtil.hasPassword());
        assertFalse(PrivacyLockUtil.isSwitchOn());
        assertFalse(PrivacyLockUtil.isEnabled());
        assertFalse(PrivacyLockUtil.verify("1234"));
        assertFalse(PrivacyLockUtil.verify(null));
        assertFalse(PrivacyLockUtil.verify(""));
    }

    @Test
    public void verifyOnlyAcceptsTheExactPassword() {
        PrivacyLockUtil.setPassword("1234");

        assertTrue(PrivacyLockUtil.hasPassword());
        assertTrue(PrivacyLockUtil.verify("1234"));
        assertFalse(PrivacyLockUtil.verify("4321"));
        assertFalse(PrivacyLockUtil.verify("1234 "));
        assertFalse(PrivacyLockUtil.verify("123"));
        assertFalse(PrivacyLockUtil.verify(""));
        assertFalse("null 不能当成空密码放行", PrivacyLockUtil.verify(null));

        // 只设了密码、没开开关：密码可校验，但锁本身不生效
        assertFalse(PrivacyLockUtil.isEnabled());
    }

    @Test
    public void enabledRequiresBothSwitchAndPassword() {
        PrivacyLockUtil.setPassword("p@ss");
        PrivacyLockUtil.setEnabled(true);

        assertTrue(PrivacyLockUtil.isSwitchOn());
        assertTrue(PrivacyLockUtil.isEnabled());

        PrivacyLockUtil.setEnabled(false);
        assertFalse(PrivacyLockUtil.isSwitchOn());
        assertFalse(PrivacyLockUtil.isEnabled());
        assertTrue("关锁不清密码，下次开锁不用重设", PrivacyLockUtil.hasPassword());

        // 只拨开关、没有密码：设置页开关是开的，但锁不生效
        sp.edit().remove(KEY_HASH).commit();
        PrivacyLockUtil.setEnabled(true);
        assertTrue(PrivacyLockUtil.isSwitchOn());
        assertFalse(PrivacyLockUtil.isEnabled());
        assertFalse(PrivacyLockUtil.hasPassword());
    }

    @Test
    public void storedValueIsSaltedSha256AndNeverPlaintext() {
        PrivacyLockUtil.setPassword("Secret123");

        String stored = sp.getString(KEY_HASH, "");
        assertTrue("摘要必须是 64 位十六进制: " + stored, stored.matches(HEX64));
        assertNotEquals("Secret123", stored);
        assertFalse("落盘内容里不能包含明文", stored.contains("Secret123"));
        // 盐是固定的，所以同一明文两次设置的摘要必须一致（可重复校验）
        String first = stored;
        PrivacyLockUtil.setPassword("Secret123");
        assertEquals(first, sp.getString(KEY_HASH, ""));

        // 不同密码摘要不同
        PrivacyLockUtil.setPassword("Secret124");
        assertNotEquals(first, sp.getString(KEY_HASH, ""));
        assertFalse(PrivacyLockUtil.verify("Secret123"));
        assertTrue(PrivacyLockUtil.verify("Secret124"));

        // 开关本身也只是布尔值
        PrivacyLockUtil.setEnabled(true);
        assertTrue(sp.getBoolean(KEY_ENABLE, false));
        assertFalse("不能存在未加盐的明文密码键", sp.contains("privacy_lock_password"));
    }

    @Test
    public void passwordSurvivesSharedPreferencesReload() {
        PrivacyLockUtil.setPassword("keep-me");
        PrivacyLockUtil.setEnabled(true);
        String stored = sp.getString(KEY_HASH, "");

        // 模拟应用重启后重新拿到 SharedPreferences 实例
        SharedPreferences reloaded = ApplicationProvider.getApplicationContext()
                .getSharedPreferences("test", Context.MODE_PRIVATE);
        assertEquals(stored, reloaded.getString(KEY_HASH, ""));
        assertTrue(reloaded.getBoolean(KEY_ENABLE, false));

        SharedPreferencesUtil.sharedPreferences = reloaded;
        assertTrue(PrivacyLockUtil.isEnabled());
        assertTrue(PrivacyLockUtil.verify("keep-me"));
        assertFalse(PrivacyLockUtil.verify("keep-you"));
    }

    @Test
    public void changingPasswordInvalidatesOldPassword() {
        PrivacyLockUtil.setPassword("old-pass");
        PrivacyLockUtil.setEnabled(true);
        assertTrue(PrivacyLockUtil.verify("old-pass"));

        PrivacyLockUtil.setPassword("new-pass");

        assertFalse(PrivacyLockUtil.verify("old-pass"));
        assertTrue(PrivacyLockUtil.verify("new-pass"));
        assertTrue(PrivacyLockUtil.isEnabled());
        assertTrue(PrivacyLockUtil.hasPassword());
    }
}
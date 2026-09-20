package com.jjmc.chromashift.database;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.jjmc.chromashift.save.PlayerSaveData;
import com.jjmc.chromashift.player.PlayerIO;
import com.jjmc.chromashift.player.Player;
import java.sql.*;
import java.util.HashMap;
import java.util.Map;

public class PlayerDAO {
    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();
        /** Save preferred player color (by name) into players table. */
        public static void savePreferredColor(int playerId, String colorName) throws SQLException {
            String sql = "UPDATE players SET preferred_color=? WHERE player_id=?";
            try (Connection conn = DatabaseConnection.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, colorName);
                ps.setInt(2, playerId);
                ps.executeUpdate();
                System.out.println("✓ Preferred color saved for player " + playerId + ": " + colorName);
            }
        }

        /** Load preferred player color name from players table. Returns null if not set. */
        public static String loadPreferredColor(int playerId) throws SQLException {
            if (!DatabaseConnection.isAvailable()) return null;
            String sql = "SELECT preferred_color FROM players WHERE player_id=?";
            try (Connection conn = DatabaseConnection.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, playerId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        String v = rs.getString("preferred_color");
                        return (v != null && !v.isEmpty()) ? v : null;
                    }
                }
            } catch (SQLException e) {
                System.err.println("[PlayerDAO] loadPreferredColor skipped: " + e.getMessage());
                return null;
            }
            return null;
        }
    
    /**
     * Create a new player and return the player_id
     */
    public static int createPlayer(String playerName, String levelId) throws SQLException {
        String sql = "INSERT INTO players (player_name, level_id) VALUES (?, ?)";
        
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            
            ps.setString(1, playerName);
            ps.setString(2, levelId);
            ps.executeUpdate();
            
            ResultSet keys = ps.getGeneratedKeys();
            if (keys.next()) {
                int playerId = keys.getInt(1);
                System.out.println("✓ Player created: " + playerName + " (ID: " + playerId + ")");
                return playerId;
            }
        }
        throw new SQLException("Failed to create player");
    }
    
    /**
     * Save player state to database - optional, fails silently if DB unavailable
     */
    public static void savePlayerState(int playerId, PlayerIO.PlayerState state) throws SQLException {
        if (!DatabaseConnection.isAvailable()) {
            // DB not available, file save already done by caller
            return;
        }
        try (Connection conn = DatabaseConnection.getConnection()) {
            boolean exists = false;
            String checkSql = "SELECT COUNT(*) FROM player_saves WHERE player_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(checkSql)) {
                ps.setInt(1, playerId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) exists = rs.getInt(1) > 0;
                }
            }
            if (exists) {
                updatePlayerSaveState(conn, playerId, state);
            } else {
                insertPlayerSaveState(conn, playerId, state);
            }
        } catch (SQLException e) {
            System.err.println("[PlayerDAO] DB save skipped (file fallback): " + e.getMessage());
            // Don't throw - let file save be primary
        }
    }

    private static void insertPlayerSaveState(Connection conn, int playerId, PlayerIO.PlayerState s) throws SQLException {
        String sql = "INSERT INTO player_saves (" +
                "player_id, x, y, velocity_x, velocity_y, facing_left, " +
                "on_ground, can_jump, dashing, dash_timer, dash_cooldown_timer, dash_used, dash_hover_remaining, " +
                "attacking, air_attacking, air_attack_timer, attack_cooldown_timer, " +
                "health_current, health_max, is_stunned, respawn_invul_remaining, respawn_stun_remaining, " +
                "diamonds, shield, key_count, potion_count, " +
                "skill_q_json, skill_e_json, skill_r_json, skill_c_json, active_skill_json, " +
                "respawn_x, respawn_y, current_level, visited_levels_json, " +
                "save_data_json, save_timestamp" +
                ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int i = 1;
            ps.setInt(i++, playerId);
            ps.setFloat(i++, s.x);
            ps.setFloat(i++, s.y);
            ps.setFloat(i++, s.velocityX);
            ps.setFloat(i++, s.velocityY);
            ps.setBoolean(i++, s.facingLeft);
            ps.setBoolean(i++, s.onGround);
            ps.setBoolean(i++, s.canJump);
            ps.setBoolean(i++, s.dashing);
            ps.setFloat(i++, s.dashTimer);
            ps.setFloat(i++, s.dashCooldownTimer);
            ps.setBoolean(i++, s.dashUsed);
            ps.setFloat(i++, s.dashHoverRemaining);
            ps.setBoolean(i++, s.attacking);
            ps.setBoolean(i++, s.airAttacking);
            ps.setFloat(i++, s.airAttackTimer);
            ps.setFloat(i++, s.attackCooldownTimer);
            ps.setFloat(i++, s.healthCurrent);
            ps.setFloat(i++, s.healthMax);
            ps.setBoolean(i++, s.isStunned);
            ps.setFloat(i++, s.respawnInvulRemaining);
            ps.setFloat(i++, s.respawnStunRemaining);
            ps.setInt(i++, s.diamonds);
            ps.setInt(i++, s.shield);
            ps.setInt(i++, s.keyCount);
            ps.setInt(i++, s.potionCount);
            ps.setString(i++, s.skillQ != null ? gson.toJson(s.skillQ) : null);
            ps.setString(i++, s.skillE != null ? gson.toJson(s.skillE) : null);
            ps.setString(i++, s.skillR != null ? gson.toJson(s.skillR) : null);
            ps.setString(i++, s.skillC != null ? gson.toJson(s.skillC) : null);
            ps.setString(i++, s.activeSkill != null ? gson.toJson(s.activeSkill) : null);
            ps.setFloat(i++, s.respawnX);
            ps.setFloat(i++, s.respawnY);
            ps.setString(i++, s.currentLevel);
            // Serialize visited levels as String[] for Gson compatibility
            String[] visitedArr = s.visitedLevels != null ? s.visitedLevels.toArray(String.class) : new String[0];
            ps.setString(i++, gson.toJson(visitedArr));
            ps.setString(i++, gson.toJson(s));
            ps.setLong(i++, System.currentTimeMillis());
            ps.executeUpdate();
            System.out.println("✓ PlayerIO state inserted (ID: " + playerId + ")");
        }
    }

    private static void updatePlayerSaveState(Connection conn, int playerId, PlayerIO.PlayerState s) throws SQLException {
        String sql = "UPDATE player_saves SET " +
                "x=?, y=?, velocity_x=?, velocity_y=?, facing_left=?, " +
                "on_ground=?, can_jump=?, dashing=?, dash_timer=?, dash_cooldown_timer=?, dash_used=?, dash_hover_remaining=?, " +
                "attacking=?, air_attacking=?, air_attack_timer=?, attack_cooldown_timer=?, " +
                "health_current=?, health_max=?, is_stunned=?, respawn_invul_remaining=?, respawn_stun_remaining=?, " +
                "diamonds=?, shield=?, key_count=?, potion_count=?, " +
                "skill_q_json=?, skill_e_json=?, skill_r_json=?, skill_c_json=?, active_skill_json=?, " +
                "respawn_x=?, respawn_y=?, current_level=?, visited_levels_json=?, " +
                "save_data_json=?, save_timestamp=?, updated_at=NOW() " +
                "WHERE player_id=?";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int i = 1;
            ps.setFloat(i++, s.x);
            ps.setFloat(i++, s.y);
            ps.setFloat(i++, s.velocityX);
            ps.setFloat(i++, s.velocityY);
            ps.setBoolean(i++, s.facingLeft);
            ps.setBoolean(i++, s.onGround);
            ps.setBoolean(i++, s.canJump);
            ps.setBoolean(i++, s.dashing);
            ps.setFloat(i++, s.dashTimer);
            ps.setFloat(i++, s.dashCooldownTimer);
            ps.setBoolean(i++, s.dashUsed);
            ps.setFloat(i++, s.dashHoverRemaining);
            ps.setBoolean(i++, s.attacking);
            ps.setBoolean(i++, s.airAttacking);
            ps.setFloat(i++, s.airAttackTimer);
            ps.setFloat(i++, s.attackCooldownTimer);
            ps.setFloat(i++, s.healthCurrent);
            ps.setFloat(i++, s.healthMax);
            ps.setBoolean(i++, s.isStunned);
            ps.setFloat(i++, s.respawnInvulRemaining);
            ps.setFloat(i++, s.respawnStunRemaining);
            ps.setInt(i++, s.diamonds);
            ps.setInt(i++, s.shield);
            ps.setInt(i++, s.keyCount);
            ps.setInt(i++, s.potionCount);
            ps.setString(i++, s.skillQ != null ? gson.toJson(s.skillQ) : null);
            ps.setString(i++, s.skillE != null ? gson.toJson(s.skillE) : null);
            ps.setString(i++, s.skillR != null ? gson.toJson(s.skillR) : null);
            ps.setString(i++, s.skillC != null ? gson.toJson(s.skillC) : null);
            ps.setString(i++, s.activeSkill != null ? gson.toJson(s.activeSkill) : null);
            ps.setFloat(i++, s.respawnX);
            ps.setFloat(i++, s.respawnY);
            ps.setString(i++, s.currentLevel);
            // Serialize visited levels as String[] for Gson compatibility
            String[] visitedArr = s.visitedLevels != null ? s.visitedLevels.toArray(String.class) : new String[0];
            ps.setString(i++, gson.toJson(visitedArr));
            ps.setString(i++, gson.toJson(s));
            ps.setLong(i++, System.currentTimeMillis());
            ps.setInt(i++, playerId);
            ps.executeUpdate();
            System.out.println("✓ PlayerIO state updated (ID: " + playerId + ")");
        }
    }
    /**
     * Save player state to database - optional fallback
     */
    public static void savePlayer(int playerId, PlayerSaveData playerData) throws SQLException {
        if (!DatabaseConnection.isAvailable()) return;
        try (Connection conn = DatabaseConnection.getConnection()) {
            boolean exists = false;
            String checkSql = "SELECT COUNT(*) FROM player_saves WHERE player_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(checkSql)) {
                ps.setInt(1, playerId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) exists = rs.getInt(1) > 0;
                }
            }
            if (exists) {
                updatePlayerSave(conn, playerId, playerData);
            } else {
                insertPlayerSave(conn, playerId, playerData);
            }
        } catch (SQLException e) {
            System.err.println("[PlayerDAO] savePlayer skipped: " + e.getMessage());
        }
    }
    
    /**
     * Insert new player save (PlayerIO-aligned fields only)
     */
    private static void insertPlayerSave(Connection conn, int playerId, PlayerSaveData playerData) throws SQLException {
        String sql = "INSERT INTO player_saves (" +
                "player_id, x, y, velocity_x, velocity_y, facing_left, " +
                "on_ground, can_jump, dashing, dash_timer, dash_cooldown_timer, dash_used, dash_hover_remaining, " +
                "attacking, air_attacking, air_attack_timer, attack_cooldown_timer, " +
                "health_current, health_max, " +
                "is_stunned, respawn_invul_remaining, respawn_stun_remaining, " +
                "diamonds, shield, key_count, potion_count, " +
                "skill_q_json, skill_e_json, skill_r_json, skill_c_json, active_skill_json, " +
                "respawn_x, respawn_y, current_level, visited_levels_json, " +
                "save_data_json, save_timestamp" +
                ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int paramIndex = 1;
            ps.setInt(paramIndex++, playerId);
            ps.setFloat(paramIndex++, playerData.x);
            ps.setFloat(paramIndex++, playerData.y);
            ps.setFloat(paramIndex++, playerData.velocityX);
            ps.setFloat(paramIndex++, playerData.velocityY);
            ps.setBoolean(paramIndex++, playerData.facingLeft);
            ps.setBoolean(paramIndex++, playerData.onGround);
            ps.setBoolean(paramIndex++, playerData.canJump);
            ps.setBoolean(paramIndex++, playerData.dashing);
            ps.setFloat(paramIndex++, playerData.dashTimer);
            ps.setFloat(paramIndex++, playerData.dashCooldownTimer);
            ps.setBoolean(paramIndex++, playerData.dashUsed);
            ps.setFloat(paramIndex++, playerData.dashHoverRemaining);
            ps.setBoolean(paramIndex++, playerData.attacking);
            ps.setBoolean(paramIndex++, playerData.airAttacking);
            ps.setFloat(paramIndex++, playerData.airAttackTimer);
            ps.setFloat(paramIndex++, playerData.attackCooldownTimer);
            ps.setFloat(paramIndex++, playerData.healthCurrent);
            ps.setFloat(paramIndex++, playerData.healthMax);
            ps.setBoolean(paramIndex++, playerData.isStunned);
            ps.setFloat(paramIndex++, playerData.respawnInvulRemaining);
            ps.setFloat(paramIndex++, playerData.respawnStunRemaining);
            ps.setInt(paramIndex++, playerData.diamonds);
            ps.setInt(paramIndex++, playerData.shield);
            ps.setInt(paramIndex++, playerData.keyCount);
            ps.setInt(paramIndex++, playerData.potionCount);
            ps.setString(paramIndex++, playerData.skillQ != null ? gson.toJson(playerData.skillQ) : null);
            ps.setString(paramIndex++, playerData.skillE != null ? gson.toJson(playerData.skillE) : null);
            ps.setString(paramIndex++, playerData.skillR != null ? gson.toJson(playerData.skillR) : null);
            ps.setString(paramIndex++, playerData.skillC != null ? gson.toJson(playerData.skillC) : null);
            ps.setString(paramIndex++, playerData.activeSkill != null ? gson.toJson(playerData.activeSkill) : null);
            ps.setFloat(paramIndex++, playerData.respawnX);
            ps.setFloat(paramIndex++, playerData.respawnY);
            ps.setString(paramIndex++, playerData.currentLevel);
            String[] visitedArrLegacy = playerData.visitedLevels != null ? playerData.visitedLevels.toArray(String.class) : new String[0];
            ps.setString(paramIndex++, gson.toJson(visitedArrLegacy));
            ps.setString(paramIndex++, gson.toJson(playerData));
            ps.setLong(paramIndex++, playerData.saveTimestamp);
            
            ps.executeUpdate();
            System.out.println("✓ Player save inserted (ID: " + playerId + ")");
        }
    }
    
    /**
     * Update existing player save (PlayerIO-aligned fields only)
     */
    private static void updatePlayerSave(Connection conn, int playerId, PlayerSaveData playerData) throws SQLException {
        String sql = "UPDATE player_saves SET " +
                "x=?, y=?, velocity_x=?, velocity_y=?, facing_left=?, " +
                "on_ground=?, can_jump=?, dashing=?, dash_timer=?, dash_cooldown_timer=?, dash_used=?, dash_hover_remaining=?, " +
                "attacking=?, air_attacking=?, air_attack_timer=?, attack_cooldown_timer=?, " +
                "health_current=?, health_max=?, " +
                "is_stunned=?, respawn_invul_remaining=?, respawn_stun_remaining=?, " +
                "diamonds=?, shield=?, key_count=?, potion_count=?, " +
                "skill_q_json=?, skill_e_json=?, skill_r_json=?, skill_c_json=?, active_skill_json=?, " +
                "respawn_x=?, respawn_y=?, current_level=?, visited_levels_json=?, " +
                "save_data_json=?, save_timestamp=?, updated_at=NOW() " +
                "WHERE player_id=?";
        
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int paramIndex = 1;
            ps.setFloat(paramIndex++, playerData.x);
            ps.setFloat(paramIndex++, playerData.y);
            ps.setFloat(paramIndex++, playerData.velocityX);
            ps.setFloat(paramIndex++, playerData.velocityY);
            ps.setBoolean(paramIndex++, playerData.facingLeft);
            ps.setBoolean(paramIndex++, playerData.onGround);
            ps.setBoolean(paramIndex++, playerData.canJump);
            ps.setBoolean(paramIndex++, playerData.dashing);
            ps.setFloat(paramIndex++, playerData.dashTimer);
            ps.setFloat(paramIndex++, playerData.dashCooldownTimer);
            ps.setBoolean(paramIndex++, playerData.dashUsed);
            ps.setFloat(paramIndex++, playerData.dashHoverRemaining);
            ps.setBoolean(paramIndex++, playerData.attacking);
            ps.setBoolean(paramIndex++, playerData.airAttacking);
            ps.setFloat(paramIndex++, playerData.airAttackTimer);
            ps.setFloat(paramIndex++, playerData.attackCooldownTimer);
            ps.setFloat(paramIndex++, playerData.healthCurrent);
            ps.setFloat(paramIndex++, playerData.healthMax);
            ps.setBoolean(paramIndex++, playerData.isStunned);
            ps.setFloat(paramIndex++, playerData.respawnInvulRemaining);
            ps.setFloat(paramIndex++, playerData.respawnStunRemaining);
            ps.setInt(paramIndex++, playerData.diamonds);
            ps.setInt(paramIndex++, playerData.shield);
            ps.setInt(paramIndex++, playerData.keyCount);
            ps.setInt(paramIndex++, playerData.potionCount);
            ps.setString(paramIndex++, playerData.skillQ != null ? gson.toJson(playerData.skillQ) : null);
            ps.setString(paramIndex++, playerData.skillE != null ? gson.toJson(playerData.skillE) : null);
            ps.setString(paramIndex++, playerData.skillR != null ? gson.toJson(playerData.skillR) : null);
            ps.setString(paramIndex++, playerData.skillC != null ? gson.toJson(playerData.skillC) : null);
            ps.setString(paramIndex++, playerData.activeSkill != null ? gson.toJson(playerData.activeSkill) : null);
            ps.setFloat(paramIndex++, playerData.respawnX);
            ps.setFloat(paramIndex++, playerData.respawnY);
            ps.setString(paramIndex++, playerData.currentLevel);
            String[] visitedArr2 = playerData.visitedLevels != null ? playerData.visitedLevels.toArray(String.class) : new String[0];
            ps.setString(paramIndex++, gson.toJson(visitedArr2));
            ps.setString(paramIndex++, gson.toJson(playerData));
            ps.setLong(paramIndex++, playerData.saveTimestamp);
            ps.setInt(paramIndex++, playerId);
            
            ps.executeUpdate();
            System.out.println("✓ Player save updated (ID: " + playerId + ")");
        }
    }
    
    /**
     * Load player save from database (PlayerIO-aligned fields only)
     */
    public static PlayerSaveData loadPlayer(int playerId) throws SQLException {
        String sql = "SELECT save_data_json FROM player_saves WHERE player_id = ?";
        
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            
            ps.setInt(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String jsonData = rs.getString("save_data_json");
                    PlayerSaveData playerData = gson.fromJson(jsonData, PlayerSaveData.class);
                    System.out.println("✓ Player loaded (ID: " + playerId + ")");
                    return playerData;
                }
            }
        }
        throw new SQLException("Player save not found for ID: " + playerId);
    }
    
    /**
     * Get player ID by name - with proper resource closing
     */
    public static int getPlayerIdByName(String playerName) throws SQLException {
        String sql = "SELECT player_id FROM players WHERE player_name = ?";
        
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            
            ps.setString(1, playerName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("player_id");
                }
            }
        }
        throw new SQLException("Player not found: " + playerName);
    }
    
    /**
     * Load player state from database and return as PlayerIO.PlayerState object.
     */
    public static com.jjmc.chromashift.player.PlayerIO.PlayerState loadPlayerStateFromDB(int playerId) throws SQLException {
        String sql = "SELECT save_data_json FROM player_saves WHERE player_id = ?";
        
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            
            ps.setInt(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String jsonData = rs.getString("save_data_json");
                    com.jjmc.chromashift.player.PlayerIO.PlayerState state = 
                        gson.fromJson(jsonData, com.jjmc.chromashift.player.PlayerIO.PlayerState.class);
                    System.out.println("✓ PlayerState loaded from database (ID: " + playerId + ")");
                    return state;
                }
            }
        }
        throw new SQLException("Player save not found for ID: " + playerId);
    }

    /**
     * Load player state from database and apply it directly to a Player instance.
     * @param playerId player ID
     * @param player Player instance to update
     */
    public static void loadPlayerStateFromDB(int playerId, com.jjmc.chromashift.player.Player player) throws SQLException {
        com.jjmc.chromashift.player.PlayerIO.PlayerState state = loadPlayerStateFromDB(playerId);
        if (state != null && player != null) {
            com.jjmc.chromashift.player.PlayerIO.applyToPlayer(player, state);
            System.out.println("✓ Player state applied (ID: " + playerId + ")");
        }
    }

    /**
     * Load visited levels from database for a player.
     * Handles both proper JSON array and legacy libGDX Array serialization.
     */
    public static com.badlogic.gdx.utils.Array<String> loadVisitedLevelsFromDB(int playerId) throws SQLException {
        String sql = "SELECT visited_levels_json FROM player_saves WHERE player_id = ?";
        
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            
            ps.setInt(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String jsonData = rs.getString("visited_levels_json");
                    if (jsonData != null && !jsonData.isEmpty()) {
                        com.badlogic.gdx.utils.Array<String> result = new com.badlogic.gdx.utils.Array<>();
                        try {
                            // Try as String[] first
                            String[] arr = gson.fromJson(jsonData, String[].class);
                            if (arr != null) {
                                for (String s : arr) result.add(s);
                            }
                        } catch (Exception e) {
                            try {
                                // Try as List
                                java.util.List<String> list = gson.fromJson(jsonData, new com.google.gson.reflect.TypeToken<java.util.List<String>>(){}.getType());
                                if (list != null) result.addAll(list.toArray(new String[0]));
                            } catch (Exception e2) {
                                // Legacy format - try to extract items array via JsonParser
                                try {
                                    com.google.gson.JsonObject obj = gson.fromJson(jsonData, com.google.gson.JsonObject.class);
                                    if (obj.has("items")) {
                                        String[] items = gson.fromJson(obj.get("items"), String[].class);
                                        if (items != null) for (String s : items) if (s != null) result.add(s);
                                    }
                                } catch (Exception ignored) {}
                            }
                        }
                        System.out.println("✓ Visited levels loaded (ID: " + playerId + "): " + result.size + " levels");
                        return result;
                    }
                }
            }
        }
        return new com.badlogic.gdx.utils.Array<>();
    }

    /**
     * Delete player and all associated saves
     */
    public static void deletePlayer(int playerId) throws SQLException {
        String sql = "DELETE FROM players WHERE player_id = ?";
        
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            
            ps.setInt(1, playerId);
            ps.executeUpdate();
            System.out.println("✓ Player deleted (ID: " + playerId + ")");
        }
    }

    /**
     * Check if a player has a saved state row.
     */
    public static boolean hasPlayerSave(int playerId) throws SQLException {
        String sql = "SELECT 1 FROM player_saves WHERE player_id = ? LIMIT 1";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * Delete only the player's save row, keeping the player record.
     */
    public static void deletePlayerSave(int playerId) throws SQLException {
        String sql = "DELETE FROM player_saves WHERE player_id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, playerId);
            ps.executeUpdate();
            System.out.println("✓ Player save deleted (ID: " + playerId + ")");
        }
    }
}
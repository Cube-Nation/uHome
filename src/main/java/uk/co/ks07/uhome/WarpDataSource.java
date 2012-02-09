package uk.co.ks07.uhome;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Server;
import org.bukkit.entity.Player;

import uk.co.ks07.uhome.locale.LocaleManager;

public class WarpDataSource {

    private static Server server;
    public final static String sqlitedb = "/uhomes.db";
    public final static String mhsqlitedb = "/homes.db.old";
    private final static String TABLE_NAME = "uhomeTable";
    private final static String HOME_TABLE = "CREATE TABLE IF NOT EXISTS `uhomeTable` ("
            + "`id` INTEGER PRIMARY KEY,"
            + "`owner` varchar(32) NOT NULL DEFAULT 'Player',"
            + "`name` varchar(32) NOT NULL DEFAULT 'home',"
            + "`world` varchar(32) NOT NULL DEFAULT '0',"
            + "`x` DOUBLE NOT NULL DEFAULT '0',"
            + "`y` DOUBLE NOT NULL DEFAULT '0',"
            + "`z` DOUBLE NOT NULL DEFAULT '0',"
            + "`yaw` smallint NOT NULL DEFAULT '0',"
            + "`pitch` smallint NOT NULL DEFAULT '0',"
            + "UNIQUE (`owner`,`name`)"
            + ");";
    private final static String INV_TABLE_NAME = "uhomeInvites";
    private final static String INV_TABLE = "CREATE TABLE IF NOT EXISTS `uhomeInvites` ("
            + "`homeid` INTEGER NOT NULL DEFAULT '0',"
            + "`player` varchar(32) NOT NULL DEFAULT 'Player',"
            + "PRIMARY KEY (`homeid`,`player`)"
            + ");";

    public static void initialize(boolean needImport, Server server, Logger log) {
        WarpDataSource.server = server;

        TableStatus status = homeTableExists(log);

        if (status == TableStatus.NONE_EXIST) {
            // No tables exist, so create them.
            createTable(log);
            // Will only be true if using SQLite, so the table wouldn't exist (new file).
            if (needImport) {
                importMyHome(log);
            }
        } else if (status == TableStatus.OLD_ONLY) {
            // Need to update the schema.
            log.info("Updating database to new format.");
            // Updates MyHome table to current format, including rename. MySQL only.
            boolean tableChanged = dbTblCheck(log);
            // Update <v1.3a format to patched format. MySQL only.
            if (!tableChanged) {
                patchMySQLTable(log);
            }
            // SQLite only, copy data into renamed new table. Not needed for MySQL.
            importOldTable(log);
        } else if (status == TableStatus.NO_ITABLE) {
            // Need to update the schema.
            log.info("Updating database to new format.");
            createInviteTable(log);
        } else {
            // Tables are fine.
            log.info("Database is up-to-date.");
        }
    }

    private static void patchMySQLTable(Logger log) {
        if (HomeConfig.usemySQL) {
            String sqlOne = "ALTER TABLE `homeTable` DROP INDEX `owner`";
            String sqlTwo = "ALTER TABLE `homeTable` RENAME TO `" + TABLE_NAME + "`, ADD UNIQUE INDEX `owner` (`owner` ASC, `name` ASC)";

            Statement ps = null;
            try {
                Connection conn = ConnectionManager.getConnection(log);
                ps = conn.createStatement();
                ps.executeUpdate(sqlOne);
                conn.commit();

                ps = conn.createStatement();
                ps.executeUpdate(sqlTwo);
                conn.commit();
            } catch (SQLException ex) {
                log.log(Level.SEVERE, "Table Update Exception", ex);
            } finally {
                try {
                    if (ps != null) {
                        ps.close();
                    }
                } catch (SQLException ex) {
                    log.log(Level.SEVERE, "Table Update Exception (on close)", ex);
                }
            }
        }
    }

    private static void importOldTable(Logger log) {
        if (!HomeConfig.usemySQL) {
            // Create the new table.
            createTable(log);
            Statement ps = null;
            try {
                Connection conn = ConnectionManager.getConnection(log);
                ps = conn.createStatement();
                ps.executeUpdate("INSERT INTO " + TABLE_NAME + " SELECT * FROM homeTable");
                conn.commit();

                ps = conn.createStatement();
                ps.executeUpdate("DROP TABLE homeTable");
                conn.commit();
            } catch (SQLException ex) {
                log.log(Level.SEVERE, "Home Import Exception", ex);
            } finally {
                try {
                    if (ps != null) {
                        ps.close();
                    }
                } catch (SQLException ex) {
                    log.log(Level.SEVERE, "Home Import Exception (on close)", ex);
                }
            }
        }
    }

    public static WarpData getMap(Logger log) {
        HashMap<String, HashMap<String, Home>> retHL = new HashMap<String, HashMap<String, Home>>();
        HashMap<String, HashSet<Home>> retIL = new HashMap<String, HashSet<Home>>();
        Statement statement = null;
        Statement invStatement = null;
        ResultSet set = null;
        ResultSet invSet = null;
        
        try {
            Connection conn = ConnectionManager.getConnection(log);

            statement = conn.createStatement();
            set = statement.executeQuery("SELECT * FROM " + TABLE_NAME);
            int size = 0;
            while (set.next()) {
                size++;
                int index = set.getInt("id");
                String name = set.getString("name");
                String owner = set.getString("owner");
                String world = set.getString("world");
                double x = set.getDouble("x");
                double y = set.getDouble("y");
                double z = set.getDouble("z");
                int yaw = set.getInt("yaw");
                int pitch = set.getInt("pitch");

                Home warp = new Home(index, owner, name, world, x, y, z, yaw, pitch);

                HashSet<String> invitees = new HashSet<String>();
                
                // Probably better to do this with a join...
                invStatement = conn.createStatement();
                invSet = invStatement.executeQuery("SELECT player FROM " + INV_TABLE_NAME + " WHERE homeid=" + index);
                while (invSet.next()) {
                    invitees.add(invSet.getString(1));
                    
                    String lowerInvitee = invSet.getString(1).toLowerCase();
                    
                    if (retIL.containsKey(lowerInvitee)) {
                        retIL.get(lowerInvitee).add(warp);
                    } else {
                        HashSet<Home> invitedHomes = new HashSet<Home>();
                        invitedHomes.add(warp);
                        retIL.put(lowerInvitee, invitedHomes);
                    }
                }

                if (!invitees.isEmpty()) {
                    warp.addInvitees(invitees);
                }
                
                if (retHL.containsKey(owner.toLowerCase())) {
                    retHL.get(owner.toLowerCase()).put(name, warp);
                } else {
                    HashMap<String, Home> ownerWarps = new HashMap<String, Home>();
                    ownerWarps.put(name, warp);
                    retHL.put(owner.toLowerCase(), ownerWarps);
                }
            }
            log.info(Integer.toString(size) + " homes loaded");
        } catch (SQLException ex) {
            log.log(Level.SEVERE, "Home Load Exception", ex);
        } finally {
            try {
                if (statement != null) {
                    statement.close();
                }
                if (set != null) {
                    set.close();
                }
            } catch (SQLException ex) {
                log.log(Level.SEVERE, "Home Load Exception (On Close)", ex);
            }
        }

        return new WarpData(retHL, retIL);
    }

    private static TableStatus homeTableExists(Logger log) {
        ResultSet rs = null;
        try {
            Connection conn = ConnectionManager.getConnection(log);
            DatabaseMetaData dbm = conn.getMetaData();
            rs = dbm.getTables(null, null, TABLE_NAME, null);
            if (!rs.next()) {
                rs = dbm.getTables(null, null, "homeTable", null);
                if (!rs.next()) {
                    return TableStatus.NONE_EXIST;
                } else {
                    return TableStatus.OLD_ONLY;
                }
            } else {
                rs = dbm.getTables(null, null, INV_TABLE_NAME, null);
                if (!rs.next()) {
                    return TableStatus.NO_ITABLE;
                } else {
                    return TableStatus.UP_TO_DATE;
                }
            }
        } catch (SQLException ex) {
            log.log(Level.SEVERE, "Table Check Exception", ex);
            return TableStatus.NONE_EXIST;
        } finally {
            try {
                if (rs != null) {
                    rs.close();
                }
            } catch (SQLException ex) {
                log.log(Level.SEVERE, "Table Check Exception (on close)", ex);
            }
        }
    }

    private static void createTable(Logger log) {
        Statement st = null;
        try {
            log.info("Creating Database...");
            Connection conn = ConnectionManager.getConnection(log);
            st = conn.createStatement();
            st.executeUpdate(HOME_TABLE);
            conn.commit();

            if (HomeConfig.usemySQL) {
                // We need to set auto increment on SQL.
                log.info("Modifying database for MySQL support");
                String sql = "ALTER TABLE `" + TABLE_NAME + "` CHANGE `id` `id` INT NOT NULL AUTO_INCREMENT ";
                st = conn.createStatement();
                st.executeUpdate(sql);
                conn.commit();

                // Check for old uhomes.db and import to mysql
                File sqlitefile = new File(HomeConfig.dataDir.getAbsolutePath() + sqlitedb);
                if (!sqlitefile.exists()) {
                    log.info("Could not find old " + sqlitedb);
                    return;
                } else {
                    log.info("Trying to import homes from uhomes.db");
                    Class.forName("org.sqlite.JDBC");
                    Connection sqliteconn = DriverManager.getConnection("jdbc:sqlite:" + HomeConfig.dataDir.getAbsolutePath() + sqlitedb);
                    sqliteconn.setAutoCommit(false);
                    Statement slstatement = sqliteconn.createStatement();
                    ResultSet slset = slstatement.executeQuery("SELECT * FROM " + TABLE_NAME);

                    int size = 0;
                    while (slset.next()) {
                        size++;
                        int index = slset.getInt("id");
                        String name = slset.getString("name");
                        String owner = slset.getString("owner");
                        String world = slset.getString("world");
                        double x = slset.getDouble("x");
                        double y = slset.getInt("y");
                        double z = slset.getDouble("z");
                        int yaw = slset.getInt("yaw");
                        int pitch = slset.getInt("pitch");
                        Home warp = new Home(index, owner, name, world, x, y, z, yaw, pitch);
                        addWarp(warp, log);
                    }
                    log.info("Imported " + Integer.toString(size) + " homes from " + sqlitedb);

                    if (slstatement != null) {
                        slstatement.close();
                    }
                    if (slset != null) {
                        slset.close();
                    }

                    if (sqliteconn != null) {
                        sqliteconn.close();
                    }
                }
            }
        } catch (SQLException e) {
            log.log(Level.SEVERE, "Create Table Exception", e);
        } catch (ClassNotFoundException e) {
            log.log(Level.SEVERE, "You need the SQLite library.", e);
        } finally {
            try {
                if (st != null) {
                    st.close();
                }
            } catch (SQLException e) {
                log.log(Level.SEVERE, "Could not create the table (on close)", e);
            }
        }

        createInviteTable(log);
    }

    private static void createInviteTable(Logger log) {
        Statement st = null;
        try {
            log.info("Creating Invite Table...");
            Connection conn = ConnectionManager.getConnection(log);
            st = conn.createStatement();
            st.executeUpdate(INV_TABLE);
            conn.commit();

            if (HomeConfig.usemySQL) {
                // Check for old uhomes.db and import to mysql
                File sqlitefile = new File(HomeConfig.dataDir.getAbsolutePath() + sqlitedb);
                if (!sqlitefile.exists()) {
                    log.info("Could not find old " + sqlitedb + ", will not import invites.");
                    return;
                } else {
                    log.info("Trying to import invites from uhomes.db");
                    Class.forName("org.sqlite.JDBC");
                    Connection sqliteconn = DriverManager.getConnection("jdbc:sqlite:" + HomeConfig.dataDir.getAbsolutePath() + sqlitedb);
                    sqliteconn.setAutoCommit(false);

                    boolean invTabExists = false;
                    ResultSet rs = null;

                    try {
                        DatabaseMetaData dbm = sqliteconn.getMetaData();
                        rs = dbm.getTables(null, null, INV_TABLE_NAME, null);
                        invTabExists = rs.next();
                    } catch (SQLException ex) {
                        log.log(Level.SEVERE, "Invite Table Check Exception", ex);
                    } finally {
                        try {
                            if (rs != null) {
                                rs.close();
                            }
                        } catch (SQLException ex) {
                            log.log(Level.SEVERE, "Invite Table Check Exception (on close)", ex);
                        }
                    }

                    if (invTabExists) {
                        Statement slstatement = sqliteconn.createStatement();
                        ResultSet slset = slstatement.executeQuery("SELECT * FROM " + INV_TABLE_NAME);

                        int size = 0;
                        while (slset.next()) {
                            size++;
                            int homeID = slset.getInt("homeid");
                            String player = slset.getString("player");
                            addInvite(homeID, player, log);
                        }
                        log.info("Imported " + Integer.toString(size) + " invites from " + sqlitedb);

                        // Rename here, we should have inserted homes previously.
                        log.info("Renaming " + sqlitedb + " to " + sqlitedb + ".old");
                        if (!sqlitefile.renameTo(new File(HomeConfig.dataDir.getAbsolutePath(), sqlitedb + ".old"))) {
                            log.warning("Failed to rename " + sqlitedb + "! Please rename this manually!");
                        }

                        if (slstatement != null) {
                            slstatement.close();
                        }
                        if (slset != null) {
                            slset.close();
                        }
                    }

                    if (sqliteconn != null) {
                        sqliteconn.close();
                    }
                }
            }
        } catch (SQLException e) {
            log.log(Level.SEVERE, "Create Invite Table Exception", e);
        } catch (ClassNotFoundException e) {
            log.log(Level.SEVERE, "You need the SQLite library.", e);
        } finally {
            try {
                if (st != null) {
                    st.close();
                }
            } catch (SQLException e) {
                log.log(Level.SEVERE, "Could not create the invite table (on close)", e);
            }
        }
    }

    public static void addWarp(Home warp, Logger log) {
        PreparedStatement ps = null;
        try {
            Connection conn = ConnectionManager.getConnection(log);

            ps = conn.prepareStatement("INSERT INTO " + TABLE_NAME + " (id, name, owner, world, x, y, z, yaw, pitch) VALUES (?,?,?,?,?,?,?,?,?)");
            ps.setInt(1, warp.index);
            ps.setString(2, warp.name);
            ps.setString(3, warp.owner);
            ps.setString(4, warp.world);
            ps.setDouble(5, warp.x);
            ps.setDouble(6, warp.y);
            ps.setDouble(7, warp.z);
            ps.setInt(8, warp.yaw);
            ps.setInt(9, warp.pitch);
            ps.executeUpdate();
            conn.commit();
        } catch (SQLException ex) {
            log.log(Level.SEVERE, "Home Insert Exception", ex);

            Player owner = server.getPlayer(warp.owner);
            if (owner != null) {
                owner.sendMessage(LocaleManager.getString("error.set.failed"));
            }
        } finally {
            try {
                if (ps != null) {
                    ps.close();
                }
            } catch (SQLException ex) {
                log.log(Level.SEVERE, "Home Insert Exception (on close)", ex);
            }
        }
    }

    public static void addInvite(int homeID, String player, Logger log) {
        PreparedStatement ps = null;
        try {
            Connection conn = ConnectionManager.getConnection(log);

            ps = conn.prepareStatement("INSERT INTO " + INV_TABLE_NAME + " (homeid, player) VALUES (?,?)");
            ps.setInt(1, homeID);
            ps.setString(2, player);
            ps.executeUpdate();
            conn.commit();
        } catch (SQLException ex) {
            log.log(Level.SEVERE, "Home Invite Insert Exception", ex);
        } finally {
            try {
                if (ps != null) {
                    ps.close();
                }
            } catch (SQLException ex) {
                log.log(Level.SEVERE, "Home Invite Insert Exception (on close)", ex);
            }
        }
    }

    public static void deleteInvite(int homeID, String player, Logger log) {
        PreparedStatement ps = null;
        try {
            Connection conn = ConnectionManager.getConnection(log);

            ps = conn.prepareStatement("DELETE FROM " + INV_TABLE_NAME + " WHERE homeid = ? AND player = ?");
            ps.setInt(1, homeID);
            ps.setString(2, player);
            ps.executeUpdate();
            conn.commit();
        } catch (SQLException ex) {
            log.log(Level.SEVERE, "Home Invite Delete Exception", ex);
        } finally {
            try {
                if (ps != null) {
                    ps.close();
                }
            } catch (SQLException ex) {
                log.log(Level.SEVERE, "Home Invite Delete Exception (on close)", ex);
            }
        }
    }

    public static void deleteWarp(Home warp, Logger log) {
        PreparedStatement ps = null;
        try {
            Connection conn = ConnectionManager.getConnection(log);

            ps = conn.prepareStatement("DELETE FROM " + TABLE_NAME + " WHERE id = ?");
            ps.setInt(1, warp.index);
            ps.executeUpdate();
            conn.commit();
        } catch (SQLException ex) {
            log.log(Level.SEVERE, "Home Delete Exception", ex);
        } finally {
            try {
                if (ps != null) {
                    ps.close();
                }
            } catch (SQLException ex) {
                log.log(Level.SEVERE, "Home Delete Exception (on close)", ex);
            }
        }
    }

    public static void moveWarp(Home warp, Logger log) {
        PreparedStatement ps = null;
        ResultSet set = null;

        try {
            Connection conn = ConnectionManager.getConnection(log);
            ps = conn.prepareStatement("UPDATE " + TABLE_NAME + " SET x = ?, y = ?, z = ?, world = ?, yaw = ?, pitch = ? WHERE id = ?");
            ps.setDouble(1, warp.x);
            ps.setDouble(2, warp.y);
            ps.setDouble(3, warp.z);
            ps.setString(4, warp.world);
            ps.setInt(5, warp.yaw);
            ps.setDouble(6, warp.pitch);
            ps.setInt(7, warp.index);
            ps.executeUpdate();
            conn.commit();
        } catch (SQLException ex) {
            log.log(Level.SEVERE, "Home Move Exception", ex);
        } finally {
            try {
                if (ps != null) {
                    ps.close();
                }
                if (set != null) {
                    set.close();
                }
            } catch (SQLException ex) {
                log.log(Level.SEVERE, "Home Move Exception (on close)", ex);
            }
        }
    }

    public static boolean dbTblCheck(Logger log) {
        // SQLite does not support field renaming or deletion, so we can't alter the table this way.
        if (HomeConfig.usemySQL) {
            String test = "SELECT `owner` FROM `homeTable`";
            String sql = "ALTER TABLE `homeTable` RENAME TO `" + TABLE_NAME + "`, CHANGE COLUMN `name` `owner` VARCHAR(32) NOT NULL DEFAULT 'Player', ADD COLUMN `name` VARCHAR(32) NOT NULL DEFAULT 'home', DROP COLUMN `publicAll`, DROP COLUMN `permissions`, DROP COLUMN `welcomeMessage`, ADD UNIQUE INDEX `owner` (`owner` ASC, `name` ASC)";
            return updateDB(test, sql, log);
        }
        // No changes.
        return false;
    }

    private static void importMyHome(Logger log) {
        try {
            if (!HomeConfig.usemySQL) {
                // Check for old homes.db and import to new db. Assume home name as 'home'
                File sqlitefile = new File(HomeConfig.dataDir.getAbsolutePath() + mhsqlitedb);
                if (!sqlitefile.exists()) {
                    log.info("Could not find " + mhsqlitedb);
                    return;
                } else {
                    log.info("Trying to import homes from homes.db.old");
                    Class.forName("org.sqlite.JDBC");
                    Connection sqliteconn = DriverManager.getConnection("jdbc:sqlite:" + HomeConfig.dataDir.getAbsolutePath() + mhsqlitedb);
                    sqliteconn.setAutoCommit(false);
                    Statement slstatement = sqliteconn.createStatement();
                    ResultSet slset = slstatement.executeQuery("SELECT * FROM homeTable");

                    int size = 0;
                    while (slset.next()) {
                        size++;
                        int index = slset.getInt("id");
                        String owner = slset.getString("name");
                        String world = slset.getString("world");
                        double x = slset.getDouble("x");
                        double y = slset.getInt("y");
                        double z = slset.getDouble("z");
                        int yaw = slset.getInt("yaw");
                        int pitch = slset.getInt("pitch");
                        Home warp = new Home(index, owner, "home", world, x, y, z, yaw, pitch);
                        addWarp(warp, log);
                    }
                    log.info("Imported " + Integer.toString(size) + " homes from " + mhsqlitedb);

                    if (slstatement != null) {
                        slstatement.close();
                    }
                    if (slset != null) {
                        slset.close();
                    }

                    if (sqliteconn != null) {
                        sqliteconn.close();
                    }
                }
            }
        } catch (SQLException e) {
            log.log(Level.SEVERE, "MyHome Import Exception", e);
        } catch (ClassNotFoundException e) {
            log.log(Level.SEVERE, "You need the SQLite library.", e);
        }
    }

    public static boolean updateDB(String test, String sql, Logger log) {
        // Use same sql for both mysql/sqlite
        return updateDB(test, sql, sql, log);
    }

    public static boolean updateDB(String test, String sqlite, String mysql, Logger log) {
        // Allowing for differences in the SQL statements for mysql/sqlite.
        try {
            Connection conn = ConnectionManager.getConnection(log);
            Statement statement = conn.createStatement();
            statement.executeQuery(test);
            statement.close();
            // No changes made, return false.
            log.info("DB test passed, no changes made.");
            return false;
        } catch (SQLException ex) {
            log.info("Backing up database for update.");
            // Failed the test so we need to execute the updates
            try {
                Connection conn = ConnectionManager.getConnection(log);
                Statement bkpStatement = conn.createStatement();
                bkpStatement.executeUpdate("DROP TABLE IF EXISTS " + TABLE_NAME + "Backup");
                bkpStatement.close();
                log.info("Updating database.");
                bkpStatement = conn.createStatement();
                bkpStatement.executeUpdate("CREATE TABLE " + TABLE_NAME + "Backup SELECT * FROM " + TABLE_NAME);
                bkpStatement.close();

                String[] query;
                if (HomeConfig.usemySQL) {
                    query = mysql.split(";");
                } else {
                    query = sqlite.split(";");
                }

                Statement sqlst = conn.createStatement();
                for (String qry : query) {
                    sqlst.executeUpdate(qry);
                }
                conn.commit();
                sqlst.close();
                // Table modified, return true.
                log.info("DB was updated.");
                return true;
            } catch (SQLException exc) {
                log.log(Level.SEVERE, "Failed to update the database to the new version - ", exc);
                return false;
            }
        }
    }

    public static void updateFieldType(String field, String type, Logger log) {
        try {
            // SQLite uses dynamic field typing so we dont need to process these.
            if (!HomeConfig.usemySQL) {
                return;
            }

            log.info("Updating database");

            Connection conn = ConnectionManager.getConnection(log);
            DatabaseMetaData meta = conn.getMetaData();

            ResultSet colRS = null;
            colRS = meta.getColumns(null, null, TABLE_NAME, null);
            while (colRS.next()) {
                String colName = colRS.getString("COLUMN_NAME");
                String colType = colRS.getString("TYPE_NAME");

                if (colName.equals(field) && !colType.equals(type)) {
                    Statement stm = conn.createStatement();
                    stm.executeUpdate("ALTER TABLE " + TABLE_NAME + " MODIFY " + field + " " + type + "; ");
                    conn.commit();
                    stm.close();
                    break;
                }
            }
            colRS.close();
        } catch (SQLException ex) {
            log.log(Level.SEVERE, "Failed to update the database to the new version - ", ex);
        }
    }

    private static enum TableStatus {
        NONE_EXIST,
        OLD_ONLY,
        UP_TO_DATE,
        NO_ITABLE;
    }
}
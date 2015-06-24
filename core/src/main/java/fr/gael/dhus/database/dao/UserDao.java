/*
 * Data Hub Service (DHuS) - For Space data distribution.
 * Copyright (C) 2013,2014,2015 GAEL Systems
 *
 * This file is part of DHuS software sources.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package fr.gael.dhus.database.dao;

import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.hibernate.HibernateException;
import org.hibernate.Query;
import org.hibernate.SQLQuery;
import org.hibernate.Session;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.orm.hibernate3.HibernateCallback;
import org.springframework.orm.hibernate3.HibernateTemplate;
import org.springframework.stereotype.Repository;

import fr.gael.dhus.database.dao.interfaces.DaoEvent;
import fr.gael.dhus.database.dao.interfaces.DaoListener;
import fr.gael.dhus.database.dao.interfaces.DaoUtils;
import fr.gael.dhus.database.dao.interfaces.HibernateDao;
import fr.gael.dhus.database.dao.interfaces.RightChangedListener;
import fr.gael.dhus.database.dao.interfaces.UserListener;
import fr.gael.dhus.database.object.Collection;
import fr.gael.dhus.database.object.FileScanner;
import fr.gael.dhus.database.object.Preference;
import fr.gael.dhus.database.object.Product;
import fr.gael.dhus.database.object.Role;
import fr.gael.dhus.database.object.Search;
import fr.gael.dhus.database.object.User;
import fr.gael.dhus.database.object.restriction.AccessRestriction;
import fr.gael.dhus.database.object.restriction.LockedAccessRestriction;
import fr.gael.dhus.database.object.restriction.TmpUserLockedAccessRestriction;
import fr.gael.dhus.service.exception.UserAlreadyExistingException;
import fr.gael.dhus.spring.context.ApplicationContextProvider;
import fr.gael.dhus.system.config.ConfigurationManager;

@Repository
public class UserDao extends HibernateDao<User, Long>
{
   @Autowired
   private CollectionDao collectionDao;
   
   @Autowired
   private ConfigurationManager cfgManager;

   @Autowired
   private ProductDao productDao;

   @Autowired
   private ProductCartDao productCartDao;

   @Autowired
   private SearchDao searchDao;
   
   @Autowired
   private AccessRestrictionDao accessRestrictionDao;

   @Autowired
   private FileScannerDao fileScannerDao;

   /**
    * Unique public data user.
    */
   private User publicData;

   /**
    * Public data username.
    */
   private final String publicDataName = "public data";

   public User getByName (final String name)
   {
      class ReturnValue
      {
         User value;
      }
      final ReturnValue rv = new ReturnValue ();

      getHibernateTemplate ().execute (new HibernateCallback<Void> ()
      {
         public Void doInHibernate (Session session) throws HibernateException,
               SQLException
         {
            rv.value =
                  (User) session.createQuery (
                        "from " + entityClass.getName () +
                              " as u where lower(u.username)=lower('" + name +
                              "')")
                        .uniqueResult ();
            return null;
         }
      });
      return rv.value;
   }

   @Override
   public void delete (final User user)
   {
      if (user == null) return;

      // remove user external references
      final long uid = user.getId ();
      productCartDao.deleteCartOfUser (user);
      getHibernateTemplate ().execute (new HibernateCallback<Void> ()
      {
         @Override
         public Void doInHibernate (Session session)
               throws HibernateException, SQLException
         {
            String sql =
                  "DELETE FROM COLLECTION_USER_AUTH WHERE USERS_ID = :uid";
            SQLQuery query = session.createSQLQuery (sql);
            query.setLong ("uid", uid);
            query.executeUpdate ();
            return null;
         }
      });
      getHibernateTemplate ().execute (new HibernateCallback<Void> ()
      {
         @Override
         public Void doInHibernate (Session session)
               throws HibernateException, SQLException
         {
            String sql = "DELETE FROM PRODUCT_USER_AUTH WHERE USERS_ID = :uid";
            SQLQuery query = session.createSQLQuery (sql);
            query.setLong ("uid", uid);
            query.executeUpdate ();
            return null;
         }
      });
      getHibernateTemplate ().execute (new HibernateCallback<Void> ()
      {
         @Override
         public Void doInHibernate (Session session)
               throws HibernateException, SQLException
         {
            String sql = "UPDATE PRODUCTS SET OWNER_ID = NULL " +
                  "WHERE OWNER_ID = :uid";
            SQLQuery query = session.createSQLQuery (sql);
            query.setLong ("uid", uid);
            query.executeUpdate ();
            return null;
         }
      });
      getHibernateTemplate ().execute (new HibernateCallback<Void> ()
      {
         @Override
         public Void doInHibernate (Session session)
               throws HibernateException, SQLException
         {
            String sql = "DELETE FROM NETWORK_USAGE WHERE USER_ID = :uid";
            SQLQuery query = session.createSQLQuery (sql);
            query.setLong ("uid", uid);
            query.executeUpdate ();
            return null;
         }
      });

      fireDeletedEvent (new DaoEvent<User> (user));
      super.delete (user);
   }
   
   public void removeUser (User user)
   {
      user.setDeleted (true);
      getHibernateTemplate ().update (user);
      productCartDao.deleteCartOfUser(user);
      fireDeletedEvent (new DaoEvent<User> (user));
   }

   private void forceDelete (User user)
   {
      super.delete (read (user.getId ()));
   }

   @SuppressWarnings ("unchecked")
   public List<User> scrollNotDeleted (final int skip, final int top)
   {
      // FIXME never call
      return getHibernateTemplate ().execute (
            new HibernateCallback<List<User>> ()
            {
               @Override
               public List<User> doInHibernate (Session session)
                     throws HibernateException, SQLException
               {
                  String hql =
                        "FROM User WHERE deleted = false AND not username = " +
                              "'" +
                              cfgManager.getAdministratorConfiguration ()
                                    .getName () +
                              " AND not username = '" + getPublicDataName () +
                              "'" +
                              " ORDER BY username";
                  Query query = session.createQuery (hql).setReadOnly (true);
                  query.setFirstResult (skip);
                  query.setMaxResults (top);
                  return (List<User>) query.list ();
               }
            });
   }

   public List<User> scrollForDataRight()
   {
      String filter =
         "WHERE deleted is false AND (not username = '" +
            cfgManager.getAdministratorConfiguration ().getName () +
            "' ORDER BY username";
      return scroll (filter, -1, -1);
   }

   @SuppressWarnings ("unchecked")
   public List<User> readNotDeleted ()
   {
      return (List<User>)find (
         "FROM " + entityClass.getName () + " u WHERE u.deleted is false and " +
            "         not u.username='" +
            cfgManager.getAdministratorConfiguration ().getName () + "'" +
            " and not u.username LIKE '"+getPublicDataName ()+"' " +
            "order by username");
   }

   public List<User> scrollNotDeleted (String filter)
   {
      return scroll ("WHERE deleted is false AND username LIKE '%" + filter +
         "%' and not username='" +
         cfgManager.getAdministratorConfiguration ().getName () + "'" +
         " and not username LIKE '" + getPublicDataName () + "' " +
         "ORDER BY username", -1, -1);
   }

   public List<User> scrollForDataRight (String filter, int skip, int top)
   {
      return scroll ("WHERE deleted is false AND username LIKE '%" + filter +
            "%' and not username='" +
            cfgManager.getAdministratorConfiguration ().getName () + "'" +
            "ORDER BY CASE username WHEN '" + getPublicDataName () +
            "' THEN 1 ELSE 2 END, username", skip, top);
   }
   
   public List<User> scrollAll (String filter, int skip, int top)
   {
      return scroll ("WHERE username LIKE '%" + filter + "%' " +
            " and not username LIKE '" + getPublicDataName () + "' " +
            " ORDER BY username", skip, top);
   }
   
   public int countNotDeleted (String filter)
   {
      return DataAccessUtils.intResult (find (
         "select count(*) FROM " + entityClass.getName () +
            " u WHERE u.deleted is false AND u.username LIKE '%" + filter +
            "%' and " + "not u.username='" +
            cfgManager.getAdministratorConfiguration ().getName () + "'" +
            " and not u.username LIKE '"+getPublicDataName ()+"' "));
   }
   
   public int countForDataRight (String filter)
   {
      return DataAccessUtils.intResult (find (
         "select count(*) FROM " + entityClass.getName () +
            " u WHERE u.deleted is false AND u.username LIKE '%" + filter +
            "%' and " + "not u.username='" +
            cfgManager.getAdministratorConfiguration ().getName () + "' "));
   }
   
   public int countAll (String filter)
   {
      return DataAccessUtils.intResult (find (
         "select count(*) FROM " + entityClass.getName () +
            " u WHERE u.username LIKE '%" + filter +"%'" +
            " and not u.username LIKE '"+getPublicDataName ()+"' " ));
   }
   
   public void addAccessToProduct (final User user, final Long pId)
   {
      if (user == null)
      {
         return;
      }
      Product product = productDao.read (pId);
      List<User> users = productDao.getAuthorizedUsers (product);
      boolean wasPublic = false;
      boolean found = false;
      final User publicDataUser = getPublicData ();
      // Check is already granted
      for (User u : users)
      {
         if (u.getId () == user.getId())
         {
            found = true;
         }
         // Can not be public data if user is already agreed
         if (u.getId () == publicDataUser.getId())
         {
            wasPublic = true;
         }
      }
      
      if (!found)
      {
         getHibernateTemplate().execute  (
            new HibernateCallback<Void>()
            {
               public Void doInHibernate(Session session) 
                  throws HibernateException, SQLException
               {
                  session.createSQLQuery(
                     "insert into PRODUCT_USER_AUTH(PRODUCTS_ID,USERS_ID) " +
                     " values(:pid, :uid)").
                     setParameter ("pid", pId).
                     setParameter ("uid", user.getId()).executeUpdate ();
                  return null;
                  
               }
            });
         fireRightAdded (new DaoEvent<User> (user), product);
      }
      if (wasPublic)
      {         
         getHibernateTemplate().execute  (
            new HibernateCallback<Void>()
            {
               public Void doInHibernate(Session session) 
                  throws HibernateException, SQLException
               {
                  session.createSQLQuery(
                     "DELETE FROM PRODUCT_USER_AUTH p " +
                     " WHERE p.PRODUCTS_ID = :pid AND p.USERS_ID = :uid").
                     setParameter ("pid", pId).
                     setParameter (
                           "uid", publicDataUser.getId()).executeUpdate ();
                  return null;
                  
               }
            });
         fireRightRemoved (new DaoEvent<User> (publicDataUser), product);
      }
   }

   public void addAccessToCollection (User user, Collection collection)
   {
      List<User> users = collectionDao.getAuthorizedUsers (collection);
      boolean wasPublic = false;
      boolean found = false;
      final User publicDataUser = getPublicData ();
      // Check is already granted
      for (User u : users)
      {
         if (u.getId () == user.getId())
         {
            found = true;
         }
         // Can not be public data if user is already agreed
         if (u.getId () == publicDataUser.getId())
         {
            wasPublic = true;
         }
      }
      if (!found)
      {
         users.add (user);
      }
//      fireRightAdded (new DaoEvent<User> (user), collection);
      if (wasPublic)
      {         
         users.remove (publicDataUser);
      }
      collection.setAuthorizedUsers (new HashSet<User> (users));
      collectionDao.update (collection);
   }

   public void removeAccessToProduct (final Long userId, final Long pId)
   {
      // if data are public, not possible to remove user right.
      if (cfgManager.isDataPublic ())
      {
         return;
      }
      Product product = productDao.read (pId);
      List<User> users = productDao.getAuthorizedUsers (product);
      // Check is already granted
      User selection = null;
      for (User u : users)
      {
         if (u.getId () == userId)
         {
            selection = u;
            break;
         }
      }
      if (selection != null)
      {
         getHibernateTemplate().execute (
               new HibernateCallback<Void> ()
               {
                  public Void doInHibernate (Session session)
                        throws HibernateException, SQLException
                  {
                     session.createSQLQuery (
                           "DELETE FROM PRODUCT_USER_AUTH p " +
                                 " WHERE p.PRODUCTS_ID = :pid AND p.USERS_ID = :uid")
                           .
                                 setParameter ("pid", pId).
                           setParameter ("uid", userId).executeUpdate ();
                     return null;
                  }
               });
         fireRightRemoved (new DaoEvent<User> (selection), product);
      }
   }

   public void removeAccessToCollection (Long userId, Collection collection)
   {
      // if data are public, not possible to remove user right.
      if (cfgManager.isDataPublic ())
      {
         return;
      }
      List<User> users = collectionDao.getAuthorizedUsers (collection);
      // Check is already granted
      User selection = null;
      for (User u : users)
      {
         if (u.getId ().equals (userId))
         {
            selection = u;
         }
      }
      if (selection != null)
      {
         users.remove (selection);
         collection.setAuthorizedUsers (new HashSet<User> (users));
         collectionDao.update (collection);
//         fireRightRemoved (new DaoEvent<User> (selection), collection);
      }
   }

   void fireRightAdded (DaoEvent<User> e, Object o)
   {
      if (o instanceof Collection) e.addParameter ("collection", o);
      if (o instanceof Product) e.addParameter ("product", o);

      for (DaoListener<?> listener : getListeners ())
      {
         if (listener instanceof RightChangedListener)
            ((RightChangedListener) listener).rightAdded (e);
      }
   }

   void fireRightRemoved (DaoEvent<User> e, Object o)
   {
      if (o instanceof Collection) e.addParameter ("collection", o);
      if (o instanceof Product) e.addParameter ("product", o);

      for (DaoListener<?> listener : getListeners ())
      {
         if (listener instanceof RightChangedListener)
            ((RightChangedListener) listener).rightRemoved (e);
      }
   }

   public String computeUserCodeForPasswordReset (User user)
   {
      if (user == null) throw new NullPointerException ("Null user.");

      if (user.getId () == null)
         throw new IllegalArgumentException ("User " + user.getUsername () +
            " must be created in the DB to compute its code.");

      String digest = user.hash ();
      String id = String.format ("%09d", user.getId ());

      String code = id + digest;

      return code;
   }

   public String computeUserCode (User user)
   {
      if (user == null) throw new NullPointerException ("Null user.");

      if (user.getId () == null)
         throw new IllegalArgumentException ("User " + user.getUsername () +
            " must be created in the DB to compute its code.");

      String digest = user.hash ();
      String id = String.format ("%09d", user.getId ());

      String code = id + digest;

      return code;
   }

   public User getUserFromUserCode (String code)
   {
      if (code == null) throw new NullPointerException ("Null code.");

      String sid = code.substring (0, 9);
      long id = Long.parseLong (sid);

      // Retrieve the user
      User user = read (id);

      if (user == null)
         throw new NullPointerException ("User cannot be retrieved for id " +
            id);

      // Check the Id
      String hash = user.hash ();

      if ( !hash.equals (code.substring (9)))
         throw new SecurityException ("Wrong hash code \"" + hash + "\".");

      return user;
   }

   public void lockUser (User user, String reason)
   {
      LockedAccessRestriction ar = new LockedAccessRestriction ();
      ar.setBlockingReason (reason);

      user.addRestriction (ar);
      update (user);
   }

   public void unlockUser (User user, Class<? extends AccessRestriction> car)
   {
      if (user.getRestrictions () == null) return;

      Iterator<AccessRestriction> iter = user.getRestrictions ().iterator ();
      HashSet<AccessRestriction> toDelete = new HashSet<AccessRestriction> ();
      while (iter.hasNext ())
      {
         AccessRestriction lar = iter.next ();
         if (lar.getClass ().equals (car))
         {
            iter.remove ();
            toDelete.add (lar);
         }
      }
      update (user);

      for (AccessRestriction restriction : toDelete)
      {
         accessRestrictionDao.delete (restriction);
      }
   }

   /**
    * Create a temporary user.
    * 
    * @param temporary user.
    * @return the updated user.
    */
   public void createTmpUser (User user)
   {
      TmpUserLockedAccessRestriction tuar =
         new TmpUserLockedAccessRestriction ();
      user.addRestriction (tuar);
      create (user);
   }

   @Override
   public User create (User u)
   {
      User user = getByName (u.getUsername ());
      if (user != null)
      {
         throw new UserAlreadyExistingException (
            "An user is already registered with name '" + u.getUsername () +
               "'.");
      }
      // Default new user come with at least search access role.
      if (u.getRoles ().isEmpty ())
      {
         u.addRole (Role.SEARCH);
         u.addRole (Role.DOWNLOAD);
      }
      return super.create (u);
   }

   public void registerTmpUser (User u)
   {
      unlockUser (u, TmpUserLockedAccessRestriction.class);
      fireUserRegister (new DaoEvent<User> (u));
   }

   public boolean isTmpUser (User u)
   {
      if (u.getRestrictions () == null)
      {
         return false;
      }
      for (AccessRestriction ar : u.getRestrictions ())
      {
         if (ar instanceof TmpUserLockedAccessRestriction)
         {
            return true;
         }
      }
      return false;
   }

   public void cleanupTmpUser (int maxDays)
   {
//      Iterator<User> sri = readAll ().iterator ();
//      while (sri.hasNext ())
//      {
//         User u = sri.next ();
//         for (AccessRestriction ar : u.getRestrictions ())
//         {
//            if (ar instanceof TmpUserLockedAccessRestriction)
//            {
//               Date creation =
//                  ((TmpUserLockedAccessRestriction) ar).getLockDate ();
//               Date now = new Date ();
//               long MILLISECONDS_PER_DAY = 1000 * 60 * 60 * 24;
//               long delta = now.getTime () - creation.getTime ();
//               if ( (delta / (MILLISECONDS_PER_DAY)) >= maxDays)
//               {
//                  logger.info ("Remove unregistered User " + u.getUsername ());
//                  forceDelete (u);
//               }
//               break;
//            }
//         }
//      }

      int skip = 0;
      final int top = DaoUtils.DEFAULT_ELEMENTS_PER_PAGE;
      long MILLISECONDS_PER_DAY = 1000 * 60 * 60 * 24;
      long runtime = System.currentTimeMillis ();
      final String hql = "SELECT u, r FROM User u LEFT OUTER JOIN " +
         "u.restrictions r WHERE r.discriminator = 'temporary'";
      List<Object[]> result;
      HibernateTemplate template = getHibernateTemplate ();
      do
      {
         final int start = skip;
         result = template.execute (new HibernateCallback<List<Object[]>>()
         {
            @Override
            @SuppressWarnings ("unchecked")
            public List<Object[]> doInHibernate (Session session)
               throws HibernateException, SQLException
            {
               Query query = session.createQuery (hql).setReadOnly (true);
               query.setFirstResult (start);
               query.setMaxResults (top);
               return (List<Object[]>) query.list ();
            }
         });
         for (Object[] objects : result)
         {
            if (objects.length != 2) continue;
            
            User user = User.class.cast (objects[0]);
            TmpUserLockedAccessRestriction restriction = 
                     TmpUserLockedAccessRestriction.class.cast (objects[1]);
            
            long date = runtime - restriction.getLockDate ().getTime ();
            if ((date / MILLISECONDS_PER_DAY) >= maxDays)
            {
               logger.info ("Remove unregistered User " + user.getUsername ());
               forceDelete (user);
            }
         }
         skip = skip + top;
      }
      while (result.size () == top);
   }

   public User getRootUser()
   {
      return getByName (cfgManager.getAdministratorConfiguration ().getName ());
   }
   
   public boolean isRootUser (User user)
   {
      if (user.getUsername ().equals (
         cfgManager.getAdministratorConfiguration ().getName ())) return true;
      return false;
   }

   void fireUserRegister (DaoEvent<User> e)
   {
      for (DaoListener<?> listener : getListeners ())
      {
         if (listener instanceof UserListener)
            ((UserListener) listener).register (e);
      }
   }

   // Preference settings
   private void updateUserPreference (User user)
   {
      getHibernateTemplate ().update (user);
   }

   public void storeUserSearch (User user, String request, String footprint,
         HashMap<String, String> advanced, String complete)
   {
      Preference pref = user.getPreferences ();
      Search search = new Search();
      search.setValue (request);
      search.setFootprint (footprint);
      search.setAdvanced (advanced);
      search.setComplete (complete);
      search.setNotify (true);
      search = searchDao.create (search);
      pref.getSearches ().add (search);
      updateUserPreference (user);
   }

   public void removeUserSearch (User user, Long id)
   {
      Search search = searchDao.read (id);
      if (search != null)
      {
         Preference pref = user.getPreferences ();
         Set<Search> s = pref.getSearches ();
         Iterator<Search> iterator = s.iterator ();
         while (iterator.hasNext ())
         {
            if (iterator.next ().equals (search))
            {
               iterator.remove ();
            }
         }
         updateUserPreference (user);
      }
      searchDao.delete (search);
   }

   public void activateUserSearchNotification (Long id, boolean notify)
   {
      Search search = searchDao.read (id);
      search.setNotify (notify);
      searchDao.update (search);
   }
   
   public void clearUserSearches (User user)
   {
      Preference pref = user.getPreferences ();
      pref.getSearches ().clear ();
      updateUserPreference (user);
   }

   public List<Search> getUserSearches (User user)
   {
      Set<Search> searches = read (user.getId ()).getPreferences ().getSearches ();
      List<Search> list = new ArrayList<> (searches);
      Collections.sort (list, new Comparator<Search>()
      {
         @Override
         public int compare (Search arg0, Search arg1)
         {
            return arg0.getValue ().compareTo (arg1.getValue ());
         }
      });      
      return list;
   }

   // File Scanner preferences
   /**
    * Add a file scanner in user preferences, if it already exists, it is
    * updated otherwise, it is created and added.
    */
   public FileScanner addFileScanner (User user, String url, String username,
         String password, String pattern, String cronSchedule,
         Set<Collection> collections)
   {
      FileScanner fs = null;
      boolean create = false;
//      if ( (fs = findFileScanner (user, url, username)) == null)
//      {
         fs = new FileScanner ();
         create = true;
//      }

      fs.setUrl (url);
      fs.setUsername (username);
      fs.setPassword (password);
      fs.setPattern (pattern);
      fs.setStatus (FileScanner.STATUS_ADDED);
      SimpleDateFormat sdf = new SimpleDateFormat (
            "EEEE dd MMMM yyyy - HH:mm:ss", Locale.ENGLISH);
      fs.setStatusMessage ("Added on "+sdf.format(new Date()));
      fs.setCollections (collections);
      fs.setCronSchedule (cronSchedule);

      // Create and retrieve the fs ibnstance in DB;
      if (create)
      {
         fileScannerDao.create (fs);
         UserDao userDao = ApplicationContextProvider.getBean (UserDao.class);
         user = userDao.read (user.getId ());
         user.getPreferences ().getFileScanners ().add (fs);
         updateUserPreference (user);
      }
      else
      {
         fileScannerDao.update (fs);
      }
      return fs;
   }
   public void updateFileScanner (Long scanId, String url, String username,
         String password, String pattern, String cronSchedule,
         Set<Collection> collections)
   {
      FileScanner fs = fileScannerDao.read (scanId);
      
      fs.setUrl (url);
      fs.setUsername (username);      
      fs.setPassword (password);
      fs.setPattern (pattern);
      fs.setStatus (FileScanner.STATUS_ADDED);
      SimpleDateFormat sdf = new SimpleDateFormat (
            "EEEE dd MMMM yyyy - HH:mm:ss", Locale.ENGLISH);
      fs.setStatusMessage ("Updated on "+sdf.format(new Date()));
      fs.setCollections (collections);
      fs.setCronSchedule (cronSchedule);

      fileScannerDao.update (fs);
   }

   public void removeFileScanner (User user, Long scannerId)
   {
      FileScanner fs = fileScannerDao.read (scannerId);
      if (fs != null)
      {
         deleteFileScanner (user, fs);
      }
   }
   
   public void setFileScannerActive (Long id, boolean active)
   {
      FileScanner fs = fileScannerDao.read (id);
      
      fs.setActive (active);

      fileScannerDao.update (fs);
   }

   private void deleteFileScanner (final User user, final FileScanner fs)
   {
      getHibernateTemplate ().execute (new HibernateCallback<Void> ()
      {
         public Void doInHibernate (Session session) throws HibernateException,
            SQLException
         {

            Long pref_id = user.getPreferences ().getId ();
            Long fs_id = fs.getId ();

            session
               .createSQLQuery (
                  "delete from FILE_SCANNER_PREFERENCES "
                     + "where FILE_SCANNER_ID=:fsid AND "
                     + "      PREFERENCE_ID= :prefid")
               .setParameter ("fsid", fs_id).setParameter ("prefid", pref_id)
               .executeUpdate ();
            return null;
         }
      });
      fileScannerDao.delete (fs);
   }

   public FileScanner findFileScanner (User user, String url, String username)
   {
      Set<FileScanner> fss = getFileScanners (user);
      for (FileScanner fs : fss)
      {
         /**
          * URL in not case sensitive instead of username is for ftp
          */
         if (url.equalsIgnoreCase (fs.getUrl ()) &&
            username.equals (fs.getUsername ()))
         {
            return fs;
         }
      }
      return null;
   }

   public Set<FileScanner> getFileScanners (User user)
   {
      return read (user.getId ()).getPreferences ().getFileScanners ();
   }

   public User getPublicData ()
   {
      User user = getByName (getPublicDataName ());
      if (user == null)
      {
         createPublicData ();
      }
      else if (publicData == null)
      {
         publicData = user;
      }
      return publicData;
   }

   private void createPublicData ()
   {
      publicData = new User();
      publicData.setUsername (getPublicDataName ());
      publicData.setPassword ("#");
      publicData.setCreated (new Date ());
      publicData = create(publicData);
   }

   public String getPublicDataName ()
   {
      return "~" + publicDataName;
   }
}

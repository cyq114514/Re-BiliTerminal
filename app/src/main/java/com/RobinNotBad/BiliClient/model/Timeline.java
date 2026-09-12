package com.RobinNotBad.BiliClient.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class Timeline implements Serializable {
    public static class DayInfo implements Serializable {
        public String date;
        public long date_ts;
        public int day_of_week;
        public List<Episode> episodes;
        public int is_today;
    }

    public static class Episode implements Serializable {
        public String cover;
        public int delay;
        public long delay_id;
        public String delay_index;
        public String delay_reason;
        public String ep_cover;
        public long episode_id;
        public String pub_index;
        public String pub_time;
        public long pub_ts;
        public int published;
        public String follows;
        public String plays;
        public long season_id;
        public String square_cover;
        public String title;
    }
}


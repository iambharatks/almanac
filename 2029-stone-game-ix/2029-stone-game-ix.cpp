class Solution {
public:
    bool stoneGameIX(vector<int>& stones) {
        vector<int> cnt(3);
        for(int i : stones){
            cnt[i%3]++;
        }
        // alice saves itself by placing the extra 3 after choosing the winning 1,2 depending upon the count (one with lower count)
        if(cnt[0]%2 == 1){
            return abs(cnt[1]-cnt[2]) > 2;
        }
        // alice saves itself by choosing the one which helps her win
        return cnt[1] >= 1 && cnt[2] >= 1;
    }
};
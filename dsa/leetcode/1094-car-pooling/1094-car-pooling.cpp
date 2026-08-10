class Solution {
public:
    bool carPooling(vector<vector<int>>& trips, int capacity) {
        vector<int> counts(1001,0);
        for(auto &trip : trips){
            counts[trip[1]] += trip[0];
            counts[trip[2]] -= trip[0];
        }
        int curCapacity = 0;
        for(int &i : counts){
            curCapacity += i;
            if(curCapacity > capacity) return false;
        }
        return true;
    }
};
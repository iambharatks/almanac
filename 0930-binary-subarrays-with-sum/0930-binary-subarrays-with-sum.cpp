class Solution {
public:
    int numSubarraysWithSum(vector<int>& nums, int goal) {
        vector<int> mp(size(nums)+1,0);
        int res = 0, sum = 0;
        mp[0] = 1;
        for(int &i : nums){
            sum += i;
            if(sum >= goal) res += mp[sum-goal];
            mp[sum]++;
        }
        return res;
    }
};
class Solution {
public:
    int largestInteger(vector<int>& nums, int k) {
        int n = size(nums);
        if(n == k){
            return *max_element(begin(nums),end(nums));
        }
        vector<int> count(51,0);
        for(int i : nums) count[i]++;
        int res = -1;
        if(k == 1){
            for(int i = 0 ; i < n; i++){
                if(count[nums[i]] == 1)
                    res = max(res,nums[i]);
            }
            return res;
        }
        res = max(res,((count[nums[0]]==1)?nums[0]:-1));
        res = max(res,((count[nums.back()]==1)?nums.back():-1));
        return res;
    }
};
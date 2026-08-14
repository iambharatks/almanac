class Solution {
public:
    int findUnsortedSubarray(vector<int>& nums) {
        int minVal = nums.back();
        int maxVal = nums[0];
        int n = size(nums);
        int l = -1, r=-1;
        for(int i = 1 ; i < n ; i++){
            maxVal = max(maxVal, nums[i]);
            if(nums[i] < maxVal){
                r = i;
            }
        }
        for(int i = n-2;  i>= 0 ; i--){
            minVal = min(minVal, nums[i]);
            if(nums[i] > minVal){
                l = i;
            }
        }
        return (r==-1)?0:r-l+1;
    }
};